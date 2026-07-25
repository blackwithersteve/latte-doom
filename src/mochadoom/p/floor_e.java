package p;

public enum floor_e {
    // lower floor to highest surrounding floor
    lowerFloor,
    
    // lower floor to lowest surrounding floor
    lowerFloorToLowest,
    
    // lower floor to highest surrounding floor VERY FAST
    turboLower,
    
    // raise floor to lowest surrounding CEILING
    raiseFloor,
    
    // raise floor to next highest surrounding floor
    raiseFloorToNearest,

    // raise floor to shortest height texture around it
    raiseToTexture,
    
    // lower floor to lowest surrounding floor
    //  and change floorpic
    lowerAndChange,
  
    raiseFloor24,
    raiseFloor24AndChange,
    raiseFloorCrush,

     // raise to next highest floor, turbo-speed
    raiseFloorTurbo,       
    donutRaise,
    raiseFloor512,

    // Latte Doom patch: Boom generalized floors (p_genlin.c):
    // plain move / move+change with special zeroed / with special copied / texture only
    genFloor,
    genFloorChg0,
    genFloorChgT,
    genFloorChg,

    // Latte Doom patch: Boom fixed types 219-222: lower floor to the next
    // lower neighbor (P_FindNextLowestFloor). Appended so vanilla ordinals hold.
    lowerFloorToNearest
}
