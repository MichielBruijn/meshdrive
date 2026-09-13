package nu.bruijn.rcvehicle.mode

/**
 * Friendly names assigned to recently-paired vehicles (ModePrefs.nameForVehicle())
 * instead of showing a raw Yggdrasil IPv6 address in the controller's recent-
 * vehicles list - per direct request. Mostly Pixar's Cars, which has by far the
 * largest cast of actual named cars, filled out with other well-known named
 * vehicles from car-culture film/TV.
 */
object VehicleNames {
    val POOL = listOf(
        // Cars (Pixar)
        "Lightning McQueen",
        "Mater",
        "Doc Hudson",
        "Sally",
        "Luigi",
        "Guido",
        "Ramone",
        "Flo",
        "Sheriff",
        "Fillmore",
        "Sarge",
        "Red",
        "Chick Hicks",
        "The King",
        "Cruz Ramirez",
        "Jackson Storm",
        "Miss Fritter",
        "Finn McMissile",
        "Holley Shiftwell",
        "Francesco Bernoulli",
        "Smokey",
        // Knight Rider
        "KITT",
        "KARR",
        // The Dukes of Hazzard
        "General Lee",
        // Transformers
        "Bumblebee",
        "Optimus Prime",
        "Megatron",
        "Ironhide",
        "Jazz",
        "Ratchet",
        // Herbie
        "Herbie",
        // Speed Racer
        "Mach 5",
        // Batman
        "Batmobile",
        // Ghostbusters
        "Ecto-1",
        // Back to the Future
        "DeLorean",
    )
}
