/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing.util;

import com.graphhopper.reader.ReaderRelation;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.profiles.EncodedValue;
import com.graphhopper.routing.profiles.FactorizedDecimalEncodedValue;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.util.Helper;
import com.graphhopper.util.PMap;

import java.util.*;

/**
 * Defines bit layout for cars. (speed, access, ferries, ...)
 *
 * @author Peter Karich
 * @author Nop
 */
public class TractorFlagEncoder extends AbstractFlagEncoder {
    protected final Map<String, Integer> trackTypeSpeedMap = new HashMap<>();
    protected final Set<String> badSurfaceSpeedMap = new HashSet<>();

    // This value determines the maximal possible speed on roads with bad surfaces
    protected int badSurfaceSpeed;

    // This value determines the speed for roads with access=destination
    protected int destinationSpeed;
    protected boolean speedTwoDirections;
    /**
     * A map which associates string to speed. Get some impression:
     * http://www.itoworld.com/map/124#fullscreen
     * http://wiki.openstreetmap.org/wiki/OSM_tags_for_routing/Maxspeed
     */
    protected final Map<String, Integer> defaultSpeedMap = new HashMap<>();

    public TractorFlagEncoder() {
        this(5, 5, 0);
    }

    public TractorFlagEncoder(PMap properties) {
        this((int) properties.getLong("speed_bits", 5),
                properties.getDouble("speed_factor", 5),
                properties.getBool("turn_costs", false) ? 1 : 0);
        this.properties = properties;
        this.speedTwoDirections = properties.getBool("speed_two_directions", false);
        this.setBlockFords(properties.getBool("block_fords", true));
        this.setBlockByDefault(properties.getBool("block_barriers", true));
    }

    public TractorFlagEncoder(String propertiesStr) {
        this(new PMap(propertiesStr));
    }

    public TractorFlagEncoder(int speedBits, double speedFactor, int maxTurnCosts) {
        super(speedBits, speedFactor, maxTurnCosts);
        restrictions.addAll(Arrays.asList("motorcar", "motor_vehicle", "vehicle", "access"));
        restrictedValues.add("private");
        restrictedValues.add("forestry");
        restrictedValues.add("no");
        restrictedValues.add("restricted");
        restrictedValues.add("delivery");
        restrictedValues.add("military");
        restrictedValues.add("emergency");

        intendedValues.add("yes");
        intendedValues.add("permissive");
        intendedValues.add("agricultural");

        potentialBarriers.add("gate");
        potentialBarriers.add("lift_gate");
        potentialBarriers.add("kissing_gate");
        potentialBarriers.add("swing_gate");
        potentialBarriers.add("sump_buster");

        absoluteBarriers.add("fence");
        absoluteBarriers.add("bollard");
        absoluteBarriers.add("stile");
        absoluteBarriers.add("turnstile");
        absoluteBarriers.add("cycle_barrier");
        absoluteBarriers.add("motorcycle_barrier");
        absoluteBarriers.add("block");
        absoluteBarriers.add("bus_trap");

        trackTypeSpeedMap.put("grade1", 30); // paved
        trackTypeSpeedMap.put("grade2", 20); // now unpaved - gravel mixed with ...
        trackTypeSpeedMap.put("grade3", 20); // ... mostly hard and some soft materials
        trackTypeSpeedMap.put("grade4", 10); // ... some hard or compressed materials
        trackTypeSpeedMap.put("grade5", 10); // ... no hard materials. soil/sand/grass


        badSurfaceSpeedMap.add("cobblestone");
        badSurfaceSpeedMap.add("grass_paver");
        badSurfaceSpeedMap.add("gravel");
        badSurfaceSpeedMap.add("sand");
        badSurfaceSpeedMap.add("paving_stones");
        badSurfaceSpeedMap.add("dirt");
        badSurfaceSpeedMap.add("ground");
        badSurfaceSpeedMap.add("grass");
        badSurfaceSpeedMap.add("unpaved");
        badSurfaceSpeedMap.add("compacted");

        // linking bigger town
        defaultSpeedMap.put("primary", 50);
        defaultSpeedMap.put("primary_link", 50);
        // linking towns + villages
        defaultSpeedMap.put("secondary", 50);
        defaultSpeedMap.put("secondary_link", 50);
        // streets without middle line separation
        defaultSpeedMap.put("tertiary", 40);
        defaultSpeedMap.put("tertiary_link", 40);
        defaultSpeedMap.put("unclassified", 30);
        defaultSpeedMap.put("residential", 30);
        // spielstraße
        defaultSpeedMap.put("living_street", 5);
        defaultSpeedMap.put("service", 20);
        // unknown road
        defaultSpeedMap.put("road", 20);
        // forestry stuff
        defaultSpeedMap.put("track", 15);

        // limit speed on bad surfaces to 30 km/h
        badSurfaceSpeed = 30;
        destinationSpeed = 5;
        maxPossibleSpeed = 50;
        speedDefault = defaultSpeedMap.get("secondary");

        init();
    }

    @Override
    public int getVersion() {
        return 2;
    }

    /**
     * Define the place of the speedBits in the edge flags for car.
     */
    @Override
    public void createEncodedValues(List<EncodedValue> registerNewEncodedValue, String prefix, int index) {
        // first two bits are reserved for route handling in superclass
        super.createEncodedValues(registerNewEncodedValue, prefix, index);
        registerNewEncodedValue.add(speedEncoder = new FactorizedDecimalEncodedValue(prefix + "average_speed", speedBits, speedFactor, speedTwoDirections));
    }

    protected double getSpeed(ReaderWay way) {
        String highwayValue = way.getTag("highway");
        Integer speed = defaultSpeedMap.get(highwayValue);
        if (speed == null)
            throw new IllegalStateException(toString() + ", no speed found for: " + highwayValue + ", tags: " + way);

        if (highwayValue.equals("track")) {
            String tt = way.getTag("tracktype");
            if (!Helper.isEmpty(tt)) {
                Integer tInt = trackTypeSpeedMap.get(tt);
                if (tInt != null)
                    speed = tInt;
            }
        }

        return speed;
    }

    @Override
    public EncodingManager.Access getAccess(ReaderWay way) {
        // TODO: Ferries have conditionals, like opening hours or are closed during some time in the year
        String highwayValue = way.getTag("highway");
        String firstValue = way.getFirstPriorityTag(restrictions);
        if (highwayValue == null) {
            if (way.hasTag("route", ferries)) {
                if (restrictedValues.contains(firstValue))
                    return EncodingManager.Access.CAN_SKIP;
                if (intendedValues.contains(firstValue) ||
                        // implied default is allowed only if foot and bicycle is not specified:
                        firstValue.isEmpty() && !way.hasTag("foot") && !way.hasTag("bicycle"))
                    return EncodingManager.Access.FERRY;
            }
            return EncodingManager.Access.CAN_SKIP;
        }

        if ("track".equals(highwayValue)) {
            String tt = way.getTag("tracktype");
            if (tt == null)
                return EncodingManager.Access.CAN_SKIP;
        }


        if (highwayValue.equals("motorway") || highwayValue.equals("motorway_link") || highwayValue.equals("trunk") || highwayValue.equals("trunk_link") || highwayValue.equals("motorroad")) {
            return EncodingManager.Access.CAN_SKIP;
        }

        if (way.hasTag("toll")) {
            if (way.getTag("toll").equals("yes"))
                return EncodingManager.Access.CAN_SKIP;
        }

        if (!defaultSpeedMap.containsKey(highwayValue))
            return EncodingManager.Access.CAN_SKIP;

        if (way.hasTag("impassable", "yes") || way.hasTag("status", "impassable"))
            return EncodingManager.Access.CAN_SKIP;

        // multiple restrictions needs special handling compared to foot and bike, see also motorcycle
        if (!firstValue.isEmpty()) {
            if (restrictedValues.contains(firstValue) && !getConditionalTagInspector().isRestrictedWayConditionallyPermitted(way))
                return EncodingManager.Access.CAN_SKIP;
            if (intendedValues.contains(firstValue))
                return EncodingManager.Access.WAY;
        }

        if (getConditionalTagInspector().isPermittedWayConditionallyRestricted(way))
            return EncodingManager.Access.CAN_SKIP;
        else
            return EncodingManager.Access.WAY;
    }

    @Override
    public long handleRelationTags(long oldRelationFlags, ReaderRelation relation) {
        return oldRelationFlags;
    }

    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way, EncodingManager.Access accept, long relationFlags) {
        if (accept.canSkip())
            return edgeFlags;

        if (!accept.isFerry()) {
            // get assumed speed from highway type
            double speed = getSpeed(way);
            speed = applyMaxSpeed(way, speed);

            speed = applyBadSurfaceSpeed(way, speed);

            setSpeed(false, edgeFlags, speed);
            setSpeed(true, edgeFlags, speed);

            boolean isRoundabout = roundaboutEnc.getBool(false, edgeFlags);
            if (isOneway(way) || isRoundabout) {
                if (isForwardOneway(way))
                    accessEnc.setBool(false, edgeFlags, true);
                if (isBackwardOneway(way))
                    accessEnc.setBool(true, edgeFlags, true);
            } else {
                accessEnc.setBool(false, edgeFlags, true);
                accessEnc.setBool(true, edgeFlags, true);
            }

        } else {
            double ferrySpeed = getFerrySpeed(way);
            accessEnc.setBool(false, edgeFlags, true);
            accessEnc.setBool(true, edgeFlags, true);
            setSpeed(false, edgeFlags, ferrySpeed);
            setSpeed(true, edgeFlags, ferrySpeed);
        }

        for (String restriction : restrictions) {
            if (way.hasTag(restriction, "destination")) {
                // This is problematic as Speed != Time
                setSpeed(false, edgeFlags, destinationSpeed);
                setSpeed(true, edgeFlags, destinationSpeed);
            }
        }
        return edgeFlags;
    }

    /**
     * make sure that isOneway is called before
     */
    protected boolean isBackwardOneway(ReaderWay way) {
        return way.hasTag("oneway", "-1")
                || way.hasTag("vehicle:forward", "no")
                || way.hasTag("motor_vehicle:forward", "no");
    }

    /**
     * make sure that isOneway is called before
     */
    protected boolean isForwardOneway(ReaderWay way) {
        return !way.hasTag("oneway", "-1")
                && !way.hasTag("vehicle:forward", "no")
                && !way.hasTag("motor_vehicle:forward", "no");
    }

    protected boolean isOneway(ReaderWay way) {
        return way.hasTag("oneway", oneways)
                || way.hasTag("vehicle:backward")
                || way.hasTag("vehicle:forward")
                || way.hasTag("motor_vehicle:backward")
                || way.hasTag("motor_vehicle:forward");
    }

    public String getWayInfo(ReaderWay way) {
        String str = "";
        String highwayValue = way.getTag("highway");
        // for now only motorway links
        if ("motorway_link".equals(highwayValue)) {
            String destination = way.getTag("destination");
            if (!Helper.isEmpty(destination)) {
                int counter = 0;
                for (String d : destination.split(";")) {
                    if (d.trim().isEmpty())
                        continue;

                    if (counter > 0)
                        str += ", ";

                    str += d.trim();
                    counter++;
                }
            }
        }
        if (str.isEmpty())
            return str;
        // I18N
        if (str.contains(","))
            return "destinations: " + str;
        else
            return "destination: " + str;
    }

    /**
     * @param way   needed to retrieve tags
     * @param speed speed guessed e.g. from the road type or other tags
     * @return The assumed speed
     */
    protected double applyBadSurfaceSpeed(ReaderWay way, double speed) {
        // limit speed if bad surface
        if (badSurfaceSpeed > 0 && speed > badSurfaceSpeed && way.hasTag("surface", badSurfaceSpeedMap))
            speed = badSurfaceSpeed;
        return speed;
    }

    @Override
    public String toString() {
        return "tractor";
    }
}
