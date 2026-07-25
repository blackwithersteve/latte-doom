package p;

public enum plattype_e {
        perpetualRaise,
        downWaitUpStay,
        raiseAndChange,
        raiseToNearestAndChange,
        blazeDWUS,

        // Latte Doom patch: Boom v2.02 instant toggle plat (types 211/212):
        // floor snaps between its floor and ceiling heights; silent, no wait, reuses
        // stasis for the "await next toggle" state. Appended so vanilla ordinals hold.
        toggleUpDn

    }