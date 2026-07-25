package data;

import defines.statenum_t;
import data.sounds.sfxenum_t;

public class mobjinfo_t {
   
        public mobjinfo_t(int doomednum, statenum_t spawnstate, int spawnhealth,
                statenum_t seestate, sfxenum_t seesound, int reactiontime,
                sfxenum_t attacksound, statenum_t painstate,
                int painchance, sfxenum_t painsound,
                statenum_t meleestate, statenum_t missilestate,
                statenum_t deathstate, statenum_t xdeathstate,
                sfxenum_t deathsound, int speed, int radius, int height,
                int mass, int damage, sfxenum_t activesound, long flags,
                statenum_t raisestate) {
            super();
            this.doomednum = doomednum;
            this.spawnstate = spawnstate;
            this.spawnhealth = spawnhealth;
            this.seestate = seestate;
            this.seesound = seesound;
            this.reactiontime = reactiontime;
            this.attacksound = attacksound;
            this.painstate = painstate;
            this.painchance = painchance;
            this.painsound = painsound;
            this.meleestate = meleestate;
            this.missilestate = missilestate;
            this.deathstate = deathstate;
            this.xdeathstate = xdeathstate;
            this.deathsound = deathsound;
            this.speed = speed;
            this.radius = radius;
            this.height = height;
            this.mass = mass;
            this.damage = damage;
            this.activesound = activesound;
            this.flags = flags;
            this.raisestate = raisestate;
        }
        
        public int doomednum;
        public statenum_t spawnstate;
        public int spawnhealth;
        public statenum_t seestate;
        public sfxenum_t seesound;
        public int reactiontime;
        public sfxenum_t attacksound;
        public statenum_t painstate;
        public int painchance;
        public sfxenum_t painsound;
        public statenum_t meleestate;
        public statenum_t missilestate;
        public statenum_t deathstate;
        public statenum_t xdeathstate;
        public sfxenum_t deathsound;
        public int speed;
        public int radius;
        public int height;
        public int mass;
        public int damage;
        public sfxenum_t activesound;
        public long flags;
        public statenum_t raisestate;

        // Latte Doom patch: DEH: int mirrors for MBF-class DEHACKED patches whose
        // state indices exceed statenum_t (>= 967). -1 = unset, follow the enum field.
        // The enum fields hold S_NULL in that case; state-machine consumer sites use the
        // *StateNum()/has*State() accessors instead (all marked at their sites).
        public int dehspawnstate = -1, dehseestate = -1, dehpainstate = -1, dehmeleestate = -1,
                   dehmissilestate = -1, dehdeathstate = -1, dehxdeathstate = -1, dehraisestate = -1;

        public int spawnStateNum() { return dehspawnstate >= 0 ? dehspawnstate : spawnstate.ordinal(); }
        public int seeStateNum() { return dehseestate >= 0 ? dehseestate : seestate.ordinal(); }
        public int painStateNum() { return dehpainstate >= 0 ? dehpainstate : painstate.ordinal(); }
        public int meleeStateNum() { return dehmeleestate >= 0 ? dehmeleestate : meleestate.ordinal(); }
        public int missileStateNum() { return dehmissilestate >= 0 ? dehmissilestate : missilestate.ordinal(); }
        public int deathStateNum() { return dehdeathstate >= 0 ? dehdeathstate : deathstate.ordinal(); }
        public int xdeathStateNum() { return dehxdeathstate >= 0 ? dehxdeathstate : xdeathstate.ordinal(); }
        public int raiseStateNum() { return dehraisestate >= 0 ? dehraisestate : raisestate.ordinal(); }

        public boolean hasSeeState() { return seeStateNum() != 0; }
        public boolean hasMeleeState() { return meleeStateNum() != 0; }
        public boolean hasMissileState() { return missileStateNum() != 0; }
        public boolean hasXDeathState() { return xdeathStateNum() != 0; }
        public boolean hasRaiseState() { return raiseStateNum() != 0; }
    }
