package com.blackwithersteve.lattedoom;

import g.Signals.ScanCode;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

/**
 * GLFW key codes to engine scan codes. The full alphabet is mapped because cheats are typed
 * rather than bound. Grave is deliberately absent: it returns control to Minecraft and must
 * never reach the engine.
 */
public final class DoomKeyMap {

    private static final Map<Integer, ScanCode> MAP = new HashMap<>();

    static {
        put(GLFW.GLFW_KEY_ESCAPE, ScanCode.SC_ESCAPE);
        put(GLFW.GLFW_KEY_1, ScanCode.SC_1);
        put(GLFW.GLFW_KEY_2, ScanCode.SC_2);
        put(GLFW.GLFW_KEY_3, ScanCode.SC_3);
        put(GLFW.GLFW_KEY_4, ScanCode.SC_4);
        put(GLFW.GLFW_KEY_5, ScanCode.SC_5);
        put(GLFW.GLFW_KEY_6, ScanCode.SC_6);
        put(GLFW.GLFW_KEY_7, ScanCode.SC_7);
        put(GLFW.GLFW_KEY_8, ScanCode.SC_8);
        put(GLFW.GLFW_KEY_9, ScanCode.SC_9);
        put(GLFW.GLFW_KEY_0, ScanCode.SC_0);
        put(GLFW.GLFW_KEY_MINUS, ScanCode.SC_MINUS);
        put(GLFW.GLFW_KEY_EQUAL, ScanCode.SC_EQUALS);
        put(GLFW.GLFW_KEY_BACKSPACE, ScanCode.SC_BACKSPACE);
        put(GLFW.GLFW_KEY_TAB, ScanCode.SC_TAB);

        put(GLFW.GLFW_KEY_Q, ScanCode.SC_Q);
        put(GLFW.GLFW_KEY_W, ScanCode.SC_W);
        put(GLFW.GLFW_KEY_E, ScanCode.SC_E);
        put(GLFW.GLFW_KEY_R, ScanCode.SC_R);
        put(GLFW.GLFW_KEY_T, ScanCode.SC_T);
        put(GLFW.GLFW_KEY_Y, ScanCode.SC_Y);
        put(GLFW.GLFW_KEY_U, ScanCode.SC_U);
        put(GLFW.GLFW_KEY_I, ScanCode.SC_I);
        put(GLFW.GLFW_KEY_O, ScanCode.SC_O);
        put(GLFW.GLFW_KEY_P, ScanCode.SC_P);
        put(GLFW.GLFW_KEY_LEFT_BRACKET, ScanCode.SC_LBRACE);
        put(GLFW.GLFW_KEY_RIGHT_BRACKET, ScanCode.SC_RBRACE);
        put(GLFW.GLFW_KEY_ENTER, ScanCode.SC_ENTER);
        put(GLFW.GLFW_KEY_LEFT_CONTROL, ScanCode.SC_LCTRL);

        put(GLFW.GLFW_KEY_A, ScanCode.SC_A);
        put(GLFW.GLFW_KEY_S, ScanCode.SC_S);
        put(GLFW.GLFW_KEY_D, ScanCode.SC_D);
        put(GLFW.GLFW_KEY_F, ScanCode.SC_F);
        put(GLFW.GLFW_KEY_G, ScanCode.SC_G);
        put(GLFW.GLFW_KEY_H, ScanCode.SC_H);
        put(GLFW.GLFW_KEY_J, ScanCode.SC_J);
        put(GLFW.GLFW_KEY_K, ScanCode.SC_K);
        put(GLFW.GLFW_KEY_L, ScanCode.SC_L);
        put(GLFW.GLFW_KEY_SEMICOLON, ScanCode.SC_SEMICOLON);
        put(GLFW.GLFW_KEY_APOSTROPHE, ScanCode.SC_QUOTE);
        put(GLFW.GLFW_KEY_LEFT_SHIFT, ScanCode.SC_LSHIFT);
        put(GLFW.GLFW_KEY_BACKSLASH, ScanCode.SC_BACKSLASH);

        put(GLFW.GLFW_KEY_Z, ScanCode.SC_Z);
        put(GLFW.GLFW_KEY_X, ScanCode.SC_X);
        put(GLFW.GLFW_KEY_C, ScanCode.SC_C);
        put(GLFW.GLFW_KEY_V, ScanCode.SC_V);
        put(GLFW.GLFW_KEY_B, ScanCode.SC_B);
        put(GLFW.GLFW_KEY_N, ScanCode.SC_N);
        put(GLFW.GLFW_KEY_M, ScanCode.SC_M);
        put(GLFW.GLFW_KEY_COMMA, ScanCode.SC_COMMA);
        put(GLFW.GLFW_KEY_PERIOD, ScanCode.SC_PERIOD);
        put(GLFW.GLFW_KEY_SLASH, ScanCode.SC_SLASH);
        put(GLFW.GLFW_KEY_RIGHT_SHIFT, ScanCode.SC_RSHIFT);

        put(GLFW.GLFW_KEY_LEFT_ALT, ScanCode.SC_LALT);
        put(GLFW.GLFW_KEY_SPACE, ScanCode.SC_SPACE);
        put(GLFW.GLFW_KEY_CAPS_LOCK, ScanCode.SC_CAPSLK);

        put(GLFW.GLFW_KEY_F1, ScanCode.SC_F1);
        put(GLFW.GLFW_KEY_F2, ScanCode.SC_F2);
        put(GLFW.GLFW_KEY_F3, ScanCode.SC_F3);
        put(GLFW.GLFW_KEY_F4, ScanCode.SC_F4);
        put(GLFW.GLFW_KEY_F5, ScanCode.SC_F5);
        put(GLFW.GLFW_KEY_F6, ScanCode.SC_F6);
        put(GLFW.GLFW_KEY_F7, ScanCode.SC_F7);
        put(GLFW.GLFW_KEY_F8, ScanCode.SC_F8);
        put(GLFW.GLFW_KEY_F9, ScanCode.SC_F9);
        put(GLFW.GLFW_KEY_F10, ScanCode.SC_F10);
        put(GLFW.GLFW_KEY_F11, ScanCode.SC_F11);
        put(GLFW.GLFW_KEY_F12, ScanCode.SC_F12);

        put(GLFW.GLFW_KEY_NUM_LOCK, ScanCode.SC_NUMLK);
        put(GLFW.GLFW_KEY_SCROLL_LOCK, ScanCode.SC_SCROLLLK);
        put(GLFW.GLFW_KEY_PRINT_SCREEN, ScanCode.SC_PRTSCRN);
        put(GLFW.GLFW_KEY_PAUSE, ScanCode.SC_PAUSE);

        put(GLFW.GLFW_KEY_KP_7, ScanCode.SC_NUMKEY7);
        put(GLFW.GLFW_KEY_KP_8, ScanCode.SC_NUMKEY8);
        put(GLFW.GLFW_KEY_KP_9, ScanCode.SC_NUMKEY9);
        put(GLFW.GLFW_KEY_KP_SUBTRACT, ScanCode.SC_NPMINUS);
        put(GLFW.GLFW_KEY_KP_4, ScanCode.SC_NUMKEY4);
        put(GLFW.GLFW_KEY_KP_5, ScanCode.SC_NUMKEY5);
        put(GLFW.GLFW_KEY_KP_6, ScanCode.SC_NUMKEY6);
        put(GLFW.GLFW_KEY_KP_ADD, ScanCode.SC_NPPLUS);
        put(GLFW.GLFW_KEY_KP_1, ScanCode.SC_NUMKEY1);
        put(GLFW.GLFW_KEY_KP_2, ScanCode.SC_NUMKEY2);
        put(GLFW.GLFW_KEY_KP_3, ScanCode.SC_NUMKEY3);
        put(GLFW.GLFW_KEY_KP_0, ScanCode.SC_NUMKEY0);
        put(GLFW.GLFW_KEY_KP_DECIMAL, ScanCode.SC_NPDOT);
        put(GLFW.GLFW_KEY_KP_ENTER, ScanCode.SC_NPENTER);
        put(GLFW.GLFW_KEY_KP_DIVIDE, ScanCode.SC_NPSLASH);
        put(GLFW.GLFW_KEY_KP_MULTIPLY, ScanCode.SC_NPMULTIPLY);

        put(GLFW.GLFW_KEY_HOME, ScanCode.SC_HOME);
        put(GLFW.GLFW_KEY_UP, ScanCode.SC_UP);
        put(GLFW.GLFW_KEY_PAGE_UP, ScanCode.SC_PGUP);
        put(GLFW.GLFW_KEY_LEFT, ScanCode.SC_LEFT);
        put(GLFW.GLFW_KEY_RIGHT, ScanCode.SC_RIGHT);
        put(GLFW.GLFW_KEY_END, ScanCode.SC_END);
        put(GLFW.GLFW_KEY_DOWN, ScanCode.SC_DOWN);
        put(GLFW.GLFW_KEY_PAGE_DOWN, ScanCode.SC_PGDOWN);
        put(GLFW.GLFW_KEY_INSERT, ScanCode.SC_INSERT);
        put(GLFW.GLFW_KEY_DELETE, ScanCode.SC_DELETE);

        put(GLFW.GLFW_KEY_RIGHT_CONTROL, ScanCode.SC_RCTRL);
        put(GLFW.GLFW_KEY_RIGHT_ALT, ScanCode.SC_RALT);
    }

    private static void put(int glfwKey, ScanCode sc) {
        MAP.put(glfwKey, sc);
    }

    /** @return the engine scan code for this GLFW key, or null if the engine has none. */
    public static ScanCode get(int glfwKey) {
        return MAP.get(glfwKey);
    }

    /** The scan code for a letter or digit, used when a cheat is typed into the engine one
     * character at a time. Null for anything else, which the caller skips rather than
     * mistypes. */
    public static ScanCode forChar(char c) {
        if (c >= 'a' && c <= 'z') {
            return MAP.get(GLFW.GLFW_KEY_A + (c - 'a'));
        }
        if (c >= '0' && c <= '9') {
            return MAP.get(GLFW.GLFW_KEY_0 + (c - '0'));
        }
        return null;
    }

    private DoomKeyMap() {}
}
