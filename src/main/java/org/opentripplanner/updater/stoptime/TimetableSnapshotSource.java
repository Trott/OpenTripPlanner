/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.updater.stoptime;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

import org.onebusaway.gtfs.model.Agency;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.model.Route;
import org.onebusaway.gtfs.model.Stop;
import org.onebusaway.gtfs.model.StopTime;
import org.onebusaway.gtfs.model.Trip;
import org.onebusaway.gtfs.model.calendar.ServiceDate;
import org.opentripplanner.model.StopPattern;
import org.opentripplanner.routing.edgetype.Timetable;
import org.opentripplanner.routing.edgetype.TripPattern;
import org.opentripplanner.routing.edgetype.TimetableSnapshot;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.GraphIndex;
import org.opentripplanner.routing.trippattern.TripTimes;
import org.opentripplanner.updater.GtfsRealtimeFuzzyTripMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;

/**
 * This class should be used to create snapshots of lookup tables of realtime data. This is
 * necessary to provide planning threads a consistent constant view of a graph with realtime data at
 * a specific point in time.
 */
public class TimetableSnapshotSource {
    private static final Logger LOG = LoggerFactory.getLogger(TimetableSnapshotSource.class);

    /**
     * Number of milliseconds per second 
     */
    private static final int MILLIS_PER_SECOND = 1000;

    /**
     * Maximum time in seconds since midnight for arrivals and departures
     */
    private static final long MAX_ARRIVAL_DEPARTURE_TIME = 48 * 60 * 60;

    public int logFrequency = 2000;

    private int appliedBlockCount = 0;

    /**
     * If a timetable snapshot is requested less than this number of milliseconds after the previous
     * snapshot, just return the same one. Throttles the potentially resource-consuming task of
     * duplicating a TripPattern -> Timetable map and indexing the new Timetables.
     */
    public int maxSnapshotFrequency = 1000; // msec

    /**
     * The last committed snapshot that was handed off to a routing thread. This snapshot may be
     * given to more than one routing thread if the maximum snapshot frequency is exceeded.
     */
    private volatile TimetableSnapshot snapshot = null;

    /**
     * The working copy of the timetable snapshot. Should not be visible to routing threads. Should
     * only be modified by a thread that holds a lock on {@link #bufferLock}. All public methods that
     * might modify this buffer will correctly acquire the lock.
     */
    private TimetableSnapshot buffer = new TimetableSnapshot();
    
    /**
     * Lock to indicate that buffer is in use
     */
    private final ReentrantLock bufferLock = new ReentrantLock(true);
    
    /**
     * A synchronized cache of trip patterns that are added to the graph due to GTFS-realtime messages.
     */
    private TripPatternCache tripPatternCache = new TripPatternCache();

    /** Should expired realtime data be purged from the graph. */
    public boolean purgeExpiredData = true;

    protected ServiceDate lastPurgeDate = null;

    protected long lastSnapshotTime = -1;

    private final TimeZone timeZone;

    private GraphIndex graphIndex;
    
    private Agency dummyAgency;

    public GtfsRealtimeFuzzyTripMatcher fuzzyTripMatcher;

    public TimetableSnapshotSource(Graph graph) {
        timeZone = graph.getTimeZone();
        graphIndex = graph.index;
        
        // Create dummy agency for added trips
        dummyAgency = new Agency();
        dummyAgency.setId("");
        dummyAgency.setName("");
    }

    /**
     * @return an up-to-date snapshot mapping TripPatterns to Timetables. This snapshot and the
     *         timetable objects it references are guaranteed to never change, so the requesting
     *         thread is provided a consistent view of all TripTimes. The routing thread need only
     *         release its reference to the snapshot to release resources.
     */
    public TimetableSnapshot getTimetableSnapshot() {
        TimetableSnapshot snapshotToReturn;
        
        // Try to get a lock on the buffer
        if (bufferLock.tryLock()) {
            // Make a new snapshot if necessary
            try {
                snapshotToReturn = getTimetableSnapshot(false);
            } finally {
                bufferLock.unlock();
            }
        } else {
            // No lock could be obtained because there is either a snapshot commit busy or updates
            // are applied at this moment, just return the current snapshot
            snapshotToReturn = snapshot;
        }
        
        return snapshotToReturn;
    }

    private TimetableSnapshot getTimetableSnapshot(boolean force) {
        long now = System.currentTimeMillis();
        if (force || now - lastSnapshotTime > maxSnapshotFrequency) {
            if (force || buffer.isDirty()) {
                LOG.debug("Committing {}", buffer.toString());
                snapshot = buffer.commit(force);
            } else {
                LOG.debug("Buffer was unchanged, keeping old snapshot.");
            }
            lastSnapshotTime = System.currentTimeMillis();
        } else {
            LOG.debug("Snapshot frequency exceeded. Reusing snapshot {}", snapshot);
        }
        return snapshot;
    }

    /**
     * Method to apply a trip update list to the most recent version of the timetable snapshot. A
     * GTFS-RT feed is always applied against a single static feed (indicated by feedId).
     * 
     * However, multi-feed support is not completed and we currently assume there is only one static
     * feed when matching IDs.
     * 
     * @param graph graph to update (needed for adding/changing stop patterns)
     * @param fullDataset true iff the list with updates represent all updates that are active right
     *        now, i.e. all previous updates should be disregarded
     * @param updates GTFS-RT TripUpdate's that should be applied atomically
     * @param feedId
     */
    public void applyTripUpdates(Graph graph, boolean fullDataset, final List<TripUpdate> updates, final String feedId) {
        if (updates == null) {
            LOG.warn("updates is null");
            return;
        }
        
        // Acquire lock on buffer
        bufferLock.lock();

        try {
            if (fullDataset) {
                // Remove all updates from the buffer
                buffer.clear();
            }

            LOG.debug("message contains {} trip updates", updates.size());
            int uIndex = 0;
            for (TripUpdate tripUpdate : updates) {
                if (fuzzyTripMatcher != null && tripUpdate.hasTrip()) {
                    TripDescriptor trip = fuzzyTripMatcher.match(feedId, tripUpdate.getTrip());
                    tripUpdate = tripUpdate.toBuilder().setTrip(trip).build();
                }

                if (!tripUpdate.hasTrip()) {
                    LOG.warn("Missing TripDescriptor in gtfs-rt trip update: \n{}", tripUpdate);
                    continue;
                }

                ServiceDate serviceDate = new ServiceDate();
                TripDescriptor tripDescriptor = tripUpdate.getTrip();

                if (tripDescriptor.hasStartDate()) {
                    try {
                        serviceDate = ServiceDate.parseString(tripDescriptor.getStartDate());
                    } catch (ParseException e) {
                        LOG.warn("Failed to parse start date in gtfs-rt trip update: \n{}", tripUpdate);
                        continue;
                    }
                } else {
                    // TODO: figure out the correct service date. For the special case that a trip
                    // starts for example at 40:00, yesterday would probably be a better guess.
                }

                uIndex += 1;
                LOG.debug("trip update #{} ({} updates) :",
                        uIndex, tripUpdate.getStopTimeUpdateCount());
                LOG.trace("{}", tripUpdate);

                // Determine what kind of trip update this is
                boolean applied = false;
                final TripDescriptor.ScheduleRelationship tripScheduleRelationship = determineTripScheduleRelationship(
                        tripUpdate);
                switch (tripScheduleRelationship) {
                    case SCHEDULED:
                        applied = handleScheduledTrip(tripUpdate, feedId, serviceDate);
                        break;
                    case ADDED:
                        applied = validateAndHandleAddedTrip(graph, tripUpdate, feedId, serviceDate);
                        break;
                    case UNSCHEDULED:
                        applied = handleUnscheduledTrip(tripUpdate, feedId, serviceDate);
                        break;
                    case CANCELED:
                        applied = handleCanceledTrip(tripUpdate, feedId, serviceDate);
                        break;
                    case MODIFIED:
                        applied = validateAndHandleModifiedTrip(graph, tripUpdate, feedId, serviceDate);
                        break;
                }

                if (applied) {
                    appliedBlockCount++;
                } else {
                    LOG.warn("Failed to apply TripUpdate.");
                    LOG.trace(" Contents: {}", tripUpdate);
                }

                if (appliedBlockCount % logFrequency == 0) {
                    LOG.info("Applied {} trip updates.", appliedBlockCount);
                }
            }
            LOG.debug("end of update message");

            // Make a snapshot after each message in anticipation of incoming requests
            // Purge data if necessary (and force new snapshot if anything was purged)
            // Make sure that the public (locking) getTimetableSnapshot function is not called.
            if (purgeExpiredData) {
                boolean modified = purgeExpiredData();
                getTimetableSnapshot(modified);
            } else {
                getTimetableSnapshot(false);
            }
        } finally {
            // Always release lock
            bufferLock.unlock();
        }
    }

    /**
     * Determine how the trip update should be handled.
     * 
     * @param tripUpdate trip update
     * @return TripDescriptor.ScheduleRelationship indicating how the trip update should be handled
     */
    private TripDescriptor.ScheduleRelationship determineTripScheduleRelationship(final TripUpdate tripUpdate) {
        // Assume default value
        TripDescriptor.ScheduleRelationship tripScheduleRelationship = TripDescriptor.ScheduleRelationship.SCHEDULED; 
        
        // If trip update contains schedule relationship, use it
        if (tripUpdate.hasTrip() && tripUpdate.getTrip().hasScheduleRelationship()) {
            tripScheduleRelationship = tripUpdate.getTrip().getScheduleRelationship();
        }
        
        if (tripScheduleRelationship.equals(TripDescriptor.ScheduleRelationship.SCHEDULED)) {
            // Loop over stops to check whether there are ADDED or SKIPPED stops
            boolean hasModifiedStops = false;
            for (StopTimeUpdate stopTimeUpdate : tripUpdate.getStopTimeUpdateList()) {
                // Check schedule relationship
                if (stopTimeUpdate.hasScheduleRelationship()) {
                    StopTimeUpdate.ScheduleRelationship stopScheduleRelationship = stopTimeUpdate
                            .getScheduleRelationship();
                    if (stopScheduleRelationship.equals(StopTimeUpdate.ScheduleRelationship.SKIPPED)
                            // TODO: uncomment next line when StopTimeUpdate.ScheduleRelationship.ADDED exists
//                            || stopScheduleRelationship.equals(StopTimeUpdate.ScheduleRelationship.ADDED)
                            ) {
                        hasModifiedStops = true;
                    }
                }
            }
            
            // If stops are modified, handle trip update like a modified trip
            if (hasModifiedStops) {
                tripScheduleRelationship = TripDescriptor.ScheduleRelationship.MODIFIED;
            }
        }
        
        return tripScheduleRelationship;
    }

    private boolean handleScheduledTrip(TripUpdate tripUpdate, String feedId, ServiceDate serviceDate) {
        TripDescriptor tripDescriptor = tripUpdate.getTrip();
        // This does not include Agency ID or feed ID, trips are feed-unique and we currently assume a single static feed.
        String tripId = tripDescriptor.getTripId();
        TripPattern pattern = getPatternForTripId(tripId);

        if (pattern == null) {
            LOG.warn("No pattern found for tripId {}, skipping TripUpdate.", tripId);
            return false;
        }

        if (tripUpdate.getStopTimeUpdateCount() < 1) {
            LOG.warn("TripUpdate contains no updates, skipping.");
            return false;
        }

        // Apply update on the *scheduled* time table and set the updated trip times in the buffer
        TripTimes updatedTripTimes = pattern.scheduledTimetable.createUpdatedTripTimes(tripUpdate,
                timeZone, serviceDate);
        
        if (updatedTripTimes == null) {
            return false;
        }
        
        boolean success = buffer.update(pattern, updatedTripTimes, serviceDate); 
        return success;
    }

    /**
     * Validate and handle GTFS-RT TripUpdate message containing an ADDED trip.
     * 
     * @param graph graph to update 
     * @param tripUpdate GTFS-RT TripUpdate message
     * @param feedId
     * @param serviceDate
     * @return true iff successful
     */
    private boolean validateAndHandleAddedTrip(final Graph graph, final TripUpdate tripUpdate,
            final String feedId, final ServiceDate serviceDate) {
        // Preconditions
        Preconditions.checkNotNull(graph);
        Preconditions.checkNotNull(tripUpdate);
        Preconditions.checkNotNull(serviceDate);
        
        //
        // Validate added trip
        //
        
        // Check whether trip id of ADDED trip is available
        TripDescriptor tripDescriptor = tripUpdate.getTrip();
        if (!tripDescriptor.hasTripId()) {
            LOG.warn("No trip id found for ADDED trip, skipping.");
            return false;
        }
        
        // Check whether trip id already exists in graph
        String tripId = tripDescriptor.getTripId();
        Trip trip = getTripForTripId(tripId);
        if (trip != null) {
            // TODO: should we support this and add a new instantiation of this trip (making it
            // frequency based)?
            LOG.warn("Graph already contains trip id of ADDED trip, skipping.");
            return false;
        }
        
        // Check whether a start date exists
        if (!tripDescriptor.hasStartDate()) {
            // TODO: should we support this and apply update to all days?
            LOG.warn("ADDED trip doesn't have a start date in TripDescriptor, skipping.");
            return false;
        }
        
        // Check whether at least two stop updates exist
        if (tripUpdate.getStopTimeUpdateCount() < 2) {
            LOG.warn("ADDED trip has less then two stops, skipping.");
            return false;
        }
        
        // Check whether all stop times are available and all stops exist
        List<Stop> stops = checkNewStopTimeUpdatesAndFindStops(tripUpdate);
        if (stops == null) {
            return false;
        }
        
        //
        // Handle added trip
        //
        
        boolean success = handleAddedTrip(graph, tripUpdate, stops, feedId, serviceDate);
        return success;
    }

    /**
     * Check stop time updates of trip update that results in a new trip (ADDED or MODIFIED) and
     * find all stops of that trip.
     * 
     * @param tripUpdate trip update
     * @return stops when stop time updates are correct; null if there are errors
     */
    private List<Stop> checkNewStopTimeUpdatesAndFindStops(final TripUpdate tripUpdate) {
        Integer previousStopSequence = null;
        Long previousTime = null;
        List<StopTimeUpdate> stopTimeUpdates = tripUpdate.getStopTimeUpdateList();
        List<Stop> stops = new ArrayList<>(stopTimeUpdates.size()); 
        for (int index = 0; index < stopTimeUpdates.size(); ++index) {
            StopTimeUpdate stopTimeUpdate = stopTimeUpdates.get(index);
            
            // Determine whether stop is skipped
            boolean skippedStop = isStopSkipped(stopTimeUpdate);
            
            // Check stop sequence
            if (stopTimeUpdate.hasStopSequence()) {
                Integer stopSequence = stopTimeUpdate.getStopSequence();
                
                // Check non-negative
                if (stopSequence < 0) {
                    LOG.warn("Trip update contains negative stop sequence, skipping.");
                    return null;
                }
                
                // Check whether sequence is increasing
                if (previousStopSequence != null && previousStopSequence > stopSequence) {
                    LOG.warn("Trip update contains decreasing stop sequence, skipping.");
                    return null;
                }
                previousStopSequence = stopSequence;
            } else {
                // Allow missing stop sequences for ADDED and MODIFIED trips
            }
            
            // Find stops
            if (stopTimeUpdate.hasStopId()) {
                // Find stop
                Stop stop = getStopForStopId(stopTimeUpdate.getStopId());
                if (stop != null) {
                    // Remember stop
                    stops.add(stop);
                } else if (skippedStop) {
                    // Set a null value for a skipped stop
                    stops.add(null);
                } else {
                    LOG.warn("Graph doesn't contain stop id \"{}\" of trip update, skipping.",
                            stopTimeUpdate.getStopId());
                    return null;
                }
            } else {
                LOG.warn("Trip update misses some stop ids, skipping.");
                return null;
            }
            
            // Only check arrival and departure times for non-skipped stops
            if (!skippedStop) {
                // Check arrival time
                if (stopTimeUpdate.hasArrival() && stopTimeUpdate.getArrival().hasTime()) {
                    // Check for increasing time
                    Long time = stopTimeUpdate.getArrival().getTime();
                    if (previousTime != null && previousTime > time) {
                        LOG.warn("Trip update contains decreasing times, skipping.");
                        return null;
                    }
                    previousTime = time;
                } else {
                    // Only first non-skipped stop is allowed to miss arrival time
                    // TODO: should we support only requiring an arrival time on the last stop and interpolate?
                    for (int earlierIndex = 0; earlierIndex < index; earlierIndex++) {
                        StopTimeUpdate earlierStopTimeUpdate = stopTimeUpdates.get(earlierIndex);
                        // Determine whether earlier stop is skipped
                        boolean earlierSkippedStop = isStopSkipped(earlierStopTimeUpdate);
                        if (!earlierSkippedStop) {
                            LOG.warn("Trip update misses arrival time, skipping.");
                            return null;
                        }
                    }
                }
                
                // Check departure time
                if (stopTimeUpdate.hasDeparture() && stopTimeUpdate.getDeparture().hasTime()) {
                    // Check for increasing time
                    Long time = stopTimeUpdate.getDeparture().getTime();
                    if (previousTime != null && previousTime > time) {
                        LOG.warn("Trip update contains decreasing times, skipping.");
                        return null;
                    }
                    previousTime = time;
                } else {
                    // Only last non-skipped stop is allowed to miss departure time
                    // TODO: should we support only requiring a departure time on the first stop and interpolate? 
                    for (int laterIndex = stopTimeUpdates.size() - 1; laterIndex > index; laterIndex--) {
                        StopTimeUpdate laterStopTimeUpdate = stopTimeUpdates.get(laterIndex);
                        // Determine whether later stop is skipped
                        boolean laterSkippedStop = isStopSkipped(laterStopTimeUpdate);
                        if (!laterSkippedStop) {
                            LOG.warn("Trip update misses departure time, skipping.");
                            return null;
                        }
                    }
                }
            }
        }
        return stops;
    }

    /**
     * Determine whether stop time update represents a SKIPPED stop.
     * 
     * @param stopTimeUpdate stop time update
     * @return true iff stop is SKIPPED; false otherwise
     */
    private boolean isStopSkipped(StopTimeUpdate stopTimeUpdate) {
        boolean isSkipped = stopTimeUpdate.hasScheduleRelationship() &&
                stopTimeUpdate.getScheduleRelationship().equals(StopTimeUpdate.ScheduleRelationship.SKIPPED); 
        return isSkipped;
    }

    /**
     * Handle GTFS-RT TripUpdate message containing an ADDED trip.
     * 
     * @param graph graph to update
     * @param tripUpdate GTFS-RT TripUpdate message
     * @param stops the stops of each StopTimeUpdate in the TripUpdate message
     * @param feedId
     * @param serviceDate service date for added trip
     * @return true iff successful
     */
    private boolean handleAddedTrip(final Graph graph, final TripUpdate tripUpdate, final List<Stop> stops,
            final String feedId, final ServiceDate serviceDate) {
        // Preconditions
        Preconditions.checkNotNull(stops);
        Preconditions.checkArgument(tripUpdate.getStopTimeUpdateCount() == stops.size(),
                "number of stop should match the number of stop time updates");
        
        // Check whether trip id has been used for previously ADDED trip message and cancel
        // previously created trip
        String tripId = tripUpdate.getTrip().getTripId();
        cancelPreviouslyAddedTrip(tripId, serviceDate);
        
        //
        // Create added trip
        //
        
        Route route = null;
        if (tripUpdate.getTrip().hasRouteId()) {
            // Try to find route
            route = getRouteForRouteId(tripUpdate.getTrip().getRouteId());
        }
        
        if (route == null) {
            // Create new Route
            route = new Route();
            // Use route id of trip descriptor if available
            if (tripUpdate.getTrip().hasRouteId()) {
                route.setId(new AgencyAndId(feedId, tripUpdate.getTrip().getRouteId()));
            } else {
                route.setId(new AgencyAndId(feedId, tripId));
            }
            route.setAgency(dummyAgency);
            // Guess the route type as it doesn't exist yet in the specifications
            route.setType(3); // Bus. Used for short- and long-distance bus routes.
            // Create route name
            route.setLongName(tripId);
        }
        
        // Create new Trip
        Trip trip = new Trip();
        // TODO: which Agency ID to use? Currently use feed id.
        trip.setId(new AgencyAndId(feedId, tripUpdate.getTrip().getTripId()));
        trip.setRoute(route);
        
        // Find service ID running on this service date
        Set<AgencyAndId> serviceIds = graph.getCalendarService().getServiceIdsOnDate(serviceDate);
        if (serviceIds.isEmpty()) {
            // No service id exists: return error for now
            LOG.warn("ADDED trip has service date for which no service id is available, skipping.");
            return false;
        } else {
            // Just use first service id of set
            trip.setServiceId(serviceIds.iterator().next());
        }
        
        boolean success = addTripToGraphAndBuffer(graph, trip, tripUpdate, stops, serviceDate); 
        return success;
    }

    /**
     * Add a (new) trip to the graph and the buffer
     * @param graph graph
     * @param trip trip
     * @param tripUpdate trip update containing stop time updates
     * @param stops list of stops corresponding to stop time updates
     * @param serviceDate service date of trip
     * 
     * @return true iff successful
     */
    private boolean addTripToGraphAndBuffer(final Graph graph, Trip trip,
            final TripUpdate tripUpdate, final List<Stop> stops, final ServiceDate serviceDate) {
        // Preconditions
        Preconditions.checkNotNull(stops);
        Preconditions.checkArgument(tripUpdate.getStopTimeUpdateCount() == stops.size(),
                "number of stop should match the number of stop time updates");
        
        // Calculate seconds since epoch on GTFS midnight (noon minus 12h) of service date 
        Calendar serviceCalendar = serviceDate.getAsCalendar(timeZone);
        final long midnightSecondsSinceEpoch = serviceCalendar.getTimeInMillis() / MILLIS_PER_SECOND;
        
        // Create StopTimes
        List<StopTime> stopTimes = new ArrayList<>(tripUpdate.getStopTimeUpdateCount());
        for (int index = 0; index < tripUpdate.getStopTimeUpdateCount(); ++index) {
            StopTimeUpdate stopTimeUpdate = tripUpdate.getStopTimeUpdate(index);
            Stop stop = stops.get(index);
            
            // Determine whether stop is skipped
            boolean skippedStop = isStopSkipped(stopTimeUpdate);
            
            // Only create stop time for non-skipped stops
            if (!skippedStop) {
                // Create stop time
                StopTime stopTime = new StopTime();
                stopTime.setTrip(trip);
                stopTime.setStop(stop);
                // Set arrival time
                if (stopTimeUpdate.hasArrival() && stopTimeUpdate.getArrival().hasTime()) {
                    long arrivalTime = stopTimeUpdate.getArrival().getTime() - midnightSecondsSinceEpoch;
                    if (arrivalTime < 0 || arrivalTime > MAX_ARRIVAL_DEPARTURE_TIME) {
                        LOG.warn("ADDED trip has invalid arrival time (compared to start date in "
                                + "TripDescriptor), skipping.");
                        return false;
                    }
                    stopTime.setArrivalTime((int) arrivalTime);
                }
                // Set departure time
                if (stopTimeUpdate.hasDeparture() && stopTimeUpdate.getDeparture().hasTime()) {
                    long departureTime = stopTimeUpdate.getDeparture().getTime() - midnightSecondsSinceEpoch;
                    if (departureTime < 0 || departureTime > MAX_ARRIVAL_DEPARTURE_TIME) {
                        LOG.warn("ADDED trip has invalid departure time (compared to start date in "
                                + "TripDescriptor), skipping.");
                        return false;
                    }
                    stopTime.setDepartureTime((int) departureTime);
                }
                stopTime.setTimepoint(1); // Exact time
                if (stopTimeUpdate.hasStopSequence()) {
                    stopTime.setStopSequence(stopTimeUpdate.getStopSequence());
                }
                // Set pickup type
                // Set different pickup type for last stop 
                if (index == tripUpdate.getStopTimeUpdateCount() - 1) {
                    stopTime.setPickupType(1); // No pickup available
                } else {
                    stopTime.setPickupType(0); // Regularly scheduled pickup
                }
                // Set drop off type
                // Set different drop off type for first stop 
                if (index == 0) {
                    stopTime.setDropOffType(1); // No drop off available
                } else {
                    stopTime.setDropOffType(0); // Regularly scheduled drop off
                }
                
                // Add stop time to list
                stopTimes.add(stopTime);
            }
        }
        
        // TODO: filter/interpolate stop times like in GTFSPatternHopFactory?
        
        // Create StopPattern
        StopPattern stopPattern = new StopPattern(stopTimes);

        // Get cached trip pattern or create one if it doesn't exist yet
        TripPattern pattern = tripPatternCache.getOrCreateTripPattern(stopPattern, trip.getRoute(), graph);
        
        // Add service code to bitset of pattern if needed (using copy on write)
        int serviceCode = graph.serviceCodes.get(trip.getServiceId());
        if (!pattern.getServices().get(serviceCode)) {
            BitSet services = (BitSet) pattern.getServices().clone();
            services.set(serviceCode);
            pattern.setServices(services);
        }
        
        // Create new trip times
        TripTimes newTripTimes = new TripTimes(trip, stopTimes, graph.deduplicator);
        
        // Update all times to mark trip times as realtime
        // TODO: should we incorporate the delay field if present?
        for (int stopIndex = 0; stopIndex < newTripTimes.getNumStops(); stopIndex++) {
            newTripTimes.updateArrivalTime(stopIndex, newTripTimes.getScheduledArrivalTime(stopIndex));
            newTripTimes.updateDepartureTime(stopIndex, newTripTimes.getScheduledDepartureTime(stopIndex));
        }
        
        // Set service code of new trip times
        newTripTimes.serviceCode = serviceCode;
        
        // Add new trip times to the buffer
        boolean success = buffer.update(pattern, newTripTimes, serviceDate);
        return success;
    }

    /**
     * Cancel scheduled trip in buffer given trip id (without agency id) on service date
     * 
     * @param tripId trip id without agency id
     * @param serviceDate service date
     * @return true if scheduled trip was cancelled
     */
    private boolean cancelScheduledTrip(String tripId, final ServiceDate serviceDate) {
        boolean success = false;
        
        TripPattern pattern = getPatternForTripId(tripId);
        if (pattern != null) {
            // Cancel scheduled trip times for this trip in this pattern
            Timetable timetable = pattern.scheduledTimetable;
            int tripIndex = timetable.getTripIndex(tripId);
            if (tripIndex == -1) {
                LOG.warn("Could not cancel scheduled trip {}", tripId);
            } else {
                TripTimes newTripTimes = new TripTimes(timetable.getTripTimes(tripIndex));
                newTripTimes.cancel();
                buffer.update(pattern, newTripTimes, serviceDate);
                success = true;
            }
        }
        
        return success;
    }

    /**
     * Cancel previously added trip from buffer if there is a previously added trip with given trip
     * id (without agency id) on service date
     * 
     * @param tripId trip id without agency id
     * @param serviceDate service date
     * @return true if a previously added trip was cancelled
     */
    private boolean cancelPreviouslyAddedTrip(String tripId, final ServiceDate serviceDate) {
        boolean success = false;
        
        TripPattern pattern = buffer.getLastAddedTripPattern(tripId, serviceDate);
        if (pattern != null) {
            // Cancel trip times for this trip in this pattern
            Timetable timetable = buffer.resolve(pattern, serviceDate);
            int tripIndex = timetable.getTripIndex(tripId);
            if (tripIndex == -1) {
                LOG.warn("Could not cancel previously added trip {}", tripId);
            } else {
                TripTimes newTripTimes = new TripTimes(timetable.getTripTimes(tripIndex));
                newTripTimes.cancel();
                buffer.update(pattern, newTripTimes, serviceDate);
                success = true;
            }
        }
        
        return success;
    }
    
    private boolean handleUnscheduledTrip(TripUpdate tripUpdate, String feedId, ServiceDate serviceDate) {
        // TODO: Handle unscheduled trip
        LOG.warn("Unscheduled trips are currently unsupported. Skipping TripUpdate.");
        return false;
    }

    /**
     * Validate and handle GTFS-RT TripUpdate message containing a MODIFIED trip.
     * 
     * @param graph graph to update 
     * @param tripUpdate GTFS-RT TripUpdate message
     * @param feedId
     * @param serviceDate
     * @return true iff successful
     */
    private boolean validateAndHandleModifiedTrip(Graph graph, TripUpdate tripUpdate, String feedId, ServiceDate serviceDate) {
        // Preconditions
        Preconditions.checkNotNull(graph);
        Preconditions.checkNotNull(tripUpdate);
        Preconditions.checkNotNull(serviceDate);
        
        //
        // Validate modified trip
        //
        
        // Check whether trip id of MODIFIED trip is available
        TripDescriptor tripDescriptor = tripUpdate.getTrip();
        if (!tripDescriptor.hasTripId()) {
            LOG.warn("No trip id found for MODIFIED trip, skipping.");
            return false;
        }
        
        // Check whether trip id already exists in graph
        String tripId = tripDescriptor.getTripId();
        Trip trip = getTripForTripId(tripId);
        if (trip == null) {
            // TODO: should we support this and consider it an ADDED trip?
            LOG.warn("Graph does not contain trip id of MODIFIED trip, skipping.");
            return false;
        }
        
        // Check whether a start date exists
        if (!tripDescriptor.hasStartDate()) {
            // TODO: should we support this and apply update to all days?
            LOG.warn("MODIFIED trip doesn't have a start date in TripDescriptor, skipping.");
            return false;
        } else {
            // Check whether service date is served by trip
            Set<AgencyAndId> serviceIds = graph.getCalendarService().getServiceIdsOnDate(serviceDate);
            if (!serviceIds.contains(trip.getServiceId())) {
                // TODO: should we support this and change service id of trip?
                LOG.warn("MODIFIED trip has a service date that is not served by trip, skipping.");
                return false;
            }
        }
        
        // Check whether at least two stop updates exist
        if (tripUpdate.getStopTimeUpdateCount() < 2) {
            LOG.warn("MODIFIED trip has less then two stops, skipping.");
            return false;
        }
        
        // Check whether all stop times are available and all stops exist
        List<Stop> stops = checkNewStopTimeUpdatesAndFindStops(tripUpdate);
        if (stops == null) {
            return false;
        }
        
        //
        // Handle modified trip
        //
        
        boolean success = handleModifiedTrip(graph, trip, tripUpdate, stops, feedId, serviceDate);
        
        return success;
    }

    /**
     * Handle GTFS-RT TripUpdate message containing a MODIFIED trip.
     * 
     * @param graph graph to update
     * @param trip trip that is modified
     * @param tripUpdate GTFS-RT TripUpdate message
     * @param stops the stops of each StopTimeUpdate in the TripUpdate message
     * @param feedId
     * @param serviceDate service date for modified trip
     * @return true iff successful
     */
    private boolean handleModifiedTrip(Graph graph, Trip trip, TripUpdate tripUpdate, List<Stop> stops,
            String feedId, ServiceDate serviceDate) {
        // Preconditions
        Preconditions.checkNotNull(stops);
        Preconditions.checkArgument(tripUpdate.getStopTimeUpdateCount() == stops.size(),
                "number of stop should match the number of stop time updates");
        
        // Cancel scheduled trip
        String tripId = tripUpdate.getTrip().getTripId();
        cancelScheduledTrip(tripId, serviceDate);
        
        // Check whether trip id has been used for previously ADDED/MODIFIED trip message and cancel
        // previously created trip
        cancelPreviouslyAddedTrip(tripId, serviceDate);
        
        // Add new trip
        boolean success = addTripToGraphAndBuffer(graph, trip, tripUpdate, stops, serviceDate); 
        return success;
    }

    private boolean handleCanceledTrip(TripUpdate tripUpdate, String feedId, ServiceDate serviceDate) {
        boolean success = false;
        if (tripUpdate.getTrip().hasTripId()) {
            // Try to cancel scheduled trip
            String tripId = tripUpdate.getTrip().getTripId();
            boolean cancelScheduledSuccess = cancelScheduledTrip(tripId, serviceDate); 
            
            // Try to cancel previously added trip
            boolean cancelPreviouslyAddedSuccess = cancelPreviouslyAddedTrip(tripId, serviceDate);
            
            if (cancelScheduledSuccess || cancelPreviouslyAddedSuccess) {
                success = true;
            } else {
                LOG.warn("No pattern found for tripId {}, skipping TripUpdate.", tripId);
            }
        } else {
            LOG.warn("No trip id in CANCELED trip update, skipping TripUpdate.");
        }
        
        return success;
    }

    private boolean purgeExpiredData() {
        ServiceDate today = new ServiceDate();
        ServiceDate previously = today.previous().previous(); // Just to be safe...

        if(lastPurgeDate != null && lastPurgeDate.compareTo(previously) > 0) {
            return false;
        }

        LOG.debug("purging expired realtime data");

        lastPurgeDate = previously;

        return buffer.purgeExpiredData(previously);
    }

    private TripPattern getPatternForTripId(String tripIdWithoutAgency) {
        Trip trip = getTripForTripId(tripIdWithoutAgency);
        TripPattern pattern = graphIndex.patternForTrip.get(trip);
        return pattern;
    }

    /**
     * Retrieve route given a route id without an agency
     * 
     * @param routeId route id without the agency
     * @return route or null if route can't be found in graph index
     */
    private Route getRouteForRouteId(String routeId) {
        /* Lazy-initialize a separate index that ignores agency IDs.
         * Stopgap measure assuming no cross-feed ID conflicts, until we get GTFS loader replaced. */
        if (graphIndex.routeForIdWithoutAgency == null) {
            Map<String, Route> map = Maps.newHashMap();
            for (Route route : graphIndex.routeForId.values()) {
                Route previousValue = map.put(route.getId().getId(), route);
                if (previousValue != null) {
                    LOG.warn("Duplicate route id detected when agency is ignored for "
                            + "realtime updates: {}", route.getId().getId());
                }
            }
            graphIndex.routeForIdWithoutAgency = map;
        }
        Route route = graphIndex.routeForIdWithoutAgency.get(routeId);
        return route;
    }
    
    /**
     * Retrieve trip given a trip id without an agency
     * 
     * @param tripId trip id without the agency
     * @return trip or null if trip can't be found in graph index
     */
    private Trip getTripForTripId(String tripId) {
        /* Lazy-initialize a separate index that ignores agency IDs.
         * Stopgap measure assuming no cross-feed ID conflicts, until we get GTFS loader replaced. */
        if (graphIndex.tripForIdWithoutAgency == null) {
            Map<String, Trip> map = Maps.newHashMap();
            for (Trip trip : graphIndex.tripForId.values()) {
                Trip previousValue = map.put(trip.getId().getId(), trip);
                if (previousValue != null) {
                    LOG.warn("Duplicate trip id detected when agency is ignored for realtime"
                            + "updates: {}", trip.getId().getId());
                }
            }
            graphIndex.tripForIdWithoutAgency = map;
        }
        Trip trip = graphIndex.tripForIdWithoutAgency.get(tripId);
        return trip;
    }

    /**
     * Retrieve stop given a stop id without an agency
     * 
     * @param stopId trip id without the agency
     * @return stop or null if stop doesn't exist
     */
    private Stop getStopForStopId(String stopId) {
        /* Lazy-initialize a separate index that ignores agency IDs.
         * Stopgap measure assuming no cross-feed ID conflicts, until we get GTFS loader replaced. */
        if (graphIndex.stopForIdWithoutAgency == null) {
            Map<String, Stop> map = Maps.newHashMap();
            for (Stop stop : graphIndex.stopForId.values()) {
                Stop previousValue = map.put(stop.getId().getId(), stop);
                if (previousValue != null) {
                    LOG.warn("Duplicate stop id detected when agency is ignored for realtime updates: {}", stopId);
                }
            }
            graphIndex.stopForIdWithoutAgency = map;
        }
        Stop stop = graphIndex.stopForIdWithoutAgency.get(stopId);
        return stop;
    }
    
}
