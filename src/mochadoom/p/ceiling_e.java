package p;

//
// P_CEILNG
//

public enum ceiling_e {

     lowerToFloor,
     raiseToHighest,
     lowerAndCrush,
     crushAndRaise,
     fastCrushAndRaise,
     silentCrushAndRaise,

     // Latte Doom patch: Boom generalized ceilings (p_genlin.c)
     genCeiling,
     genCeilingChg0,
     genCeilingChgT,
     genCeilingChg,

     // Latte Doom patch: Boom fixed types 199-206 (jff 02/04/98):
     // lower ceiling to lowest surrounding ceiling / to highest surrounding
     // floor. Appended so vanilla ordinals hold.
     lowerToLowest,
     lowerToMaxFloor;

 }
