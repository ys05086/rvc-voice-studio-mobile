package dev.virtualmic;

import org.junit.Test;
import static org.junit.Assert.*;

public class InputScopeTest {
    @Test public void globalIncludesBothProfilesButExcludesCaptureAndConverter() {
        InputScope scope = new InputScope(-1, true, 2000, 10731);
        assertTrue(scope.matches(10373));
        assertTrue(scope.matches(15010136));
        assertFalse(scope.matches(2000));
        assertFalse(scope.matches(10731));
        assertFalse(scope.matches(-1));
    }
    @Test public void selectedAppKeepsExactProfile() {
        InputScope scope = new InputScope(15010136, false, 2000, 10731);
        assertTrue(scope.matches(15010136));
        assertFalse(scope.matches(10136));
        assertFalse(scope.matches(10373));
    }
}
