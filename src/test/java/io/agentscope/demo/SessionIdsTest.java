package io.agentscope.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SessionIdsTest {

    @Test
    void acceptsSafeIds() {
        assertEquals("user-1_ab", SessionIds.requireSafeSessionId("  user-1_ab  "));
    }

    @Test
    void rejectsTraversal() {
        assertThrows(IllegalArgumentException.class, () -> SessionIds.requireSafeSessionId("../x"));
        assertThrows(IllegalArgumentException.class, () -> SessionIds.requireSafeSessionId("a/b"));
    }

    @Test
    void rejectsNonAsciiLetters() {
        assertThrows(IllegalArgumentException.class, () -> SessionIds.requireSafeSessionId("用户"));
    }
}
