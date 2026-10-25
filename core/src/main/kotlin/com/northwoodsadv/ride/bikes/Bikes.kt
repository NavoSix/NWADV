package com.northwoodsadv.ride.bikes

import com.northwoodsadv.ride.audio.EngineSound
import com.northwoodsadv.ride.physics.BikeSpec
import com.northwoodsadv.ride.render.BikeLook
import com.northwoodsadv.ride.render.GenericTwinLook
import com.northwoodsadv.ride.render.HimalayanLook

/**
 * One rideable motorcycle: its physics, its engine note and how it looks. Club members' bikes
 * are added to [Bikes.all] one at a time.
 */
class BikeModel(
    val id: String,
    val name: String,
    /** Whose bike it is (shown on the selection screen). */
    val owner: String,
    val blurb: String,
    val spec: BikeSpec,
    val sound: EngineSound,
    val look: () -> BikeLook,
    /** Headline numbers for the selection screen. */
    val engine: String,
    val power: String,
)

object Bikes {
    /**
     * Royal Enfield Himalayan 411 (BS6, 2021 on): 411 cc air/oil-cooled SOHC single, 24.3 hp at
     * 6,500 rpm and 32 N m at 4,000-4,500 rpm, 5-speed, 199 kg wet plus about 8 kg of crash bars,
     * tank guards, racks and skid plate. 21"/17" spoked wheels (90/90-21, 120/90-17), 41 mm fork
     * with 200 mm travel, linked monoshock with 180 mm, 1,465 mm wheelbase, 26.5 deg rake.
     */
    val HIMALAYAN_411 = BikeModel(
        id = "himalayan411",
        name = "Royal Enfield Himalayan 411",
        owner = "Your bike",
        blurb = "Rock Red, tall screen, Tusk handguards, crash bars, tank guards and racks. A torquey single that thumps up anything slowly.",
        spec = BikeSpec(
            name = "Royal Enfield Himalayan 411",
            massBike = 207.0,
            massRider = 90.0,
            wheelbase = 1.465,
            cgToFront = 0.79,
            cgHeightSeated = 0.79,
            cgHeightStanding = 0.91,
            rollInertia = 34.0,
            pitchInertia = 100.0,
            yawInertia = 70.0,
            frontRadius = 0.348,
            rearRadius = 0.324,
            frontWheelInertia = 0.60,
            rearWheelInertia = 0.85,
            unsprungFront = 15.0,
            unsprungRear = 18.0,
            frontTravel = 0.20,
            rearTravel = 0.18,
            // About a third of the travel as sag with the rider aboard, like the plush default.
            frontSpring = 19_200.0,
            rearSpring = 24_900.0,
            frontCompDamp = 950.0,
            frontRebDamp = 1_720.0,
            rearCompDamp = 1_100.0,
            rearRebDamp = 1_900.0,
            torqueRpm = doubleArrayOf(1000.0, 2000.0, 3000.0, 4000.0, 4500.0, 5000.0, 6000.0, 6500.0, 7000.0),
            torqueNm = doubleArrayOf(21.0, 26.0, 29.5, 32.0, 32.0, 31.2, 28.6, 26.6, 22.0),
            idleRpm = 1250.0,
            redlineRpm = 7000.0,
            // Crank, clutch and gearbox as seen at the crank; through an 18:1 first gear even this
            // adds ~110 kg of effective mass.
            engineInertia = 0.035,
            primaryRatio = 2.313,
            gearRatios = doubleArrayOf(2.833, 1.875, 1.381, 1.087, 0.903),
            finalRatio = 42.0 / 15.0,
            frontBrakeMax = 1_150.0,
            rearBrakeMax = 420.0,
            dragArea = 0.58,
            groundClearanceLean = Math.toRadians(45.0),
        ),
        sound = EngineSound.SINGLE,
        look = { HimalayanLook() },
        engine = "411 cc single",
        power = "24 hp / 32 N m",
    )

    /** The original stand-in: a generic 100 hp mid-weight adventure twin. */
    val GENERIC_TWIN = BikeModel(
        id = "generic-twin",
        name = "Mid-weight ADV twin",
        owner = "Loaner",
        blurb = "A generic 100 hp parallel twin with long-travel suspension and aluminium panniers.",
        spec = BikeSpec(),
        sound = EngineSound.TWIN_270,
        look = { GenericTwinLook() },
        engine = "~900 cc parallel twin",
        power = "100 hp / 104 N m",
    )

    val all = listOf(HIMALAYAN_411, GENERIC_TWIN)

    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: all.first()
}
