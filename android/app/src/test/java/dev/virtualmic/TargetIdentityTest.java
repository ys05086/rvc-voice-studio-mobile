package dev.virtualmic;

import org.junit.Test;
import static org.junit.Assert.*;

public class TargetIdentityTest {
    @Test public void rejectsPrimaryProfileDifferentAppAndRedactedIdentity() {
        assertTrue(TargetIdentity.matchesOtherProfile(10136, 15010136, "com.kakao.talk", "com.kakao.talk"));
        assertFalse(TargetIdentity.matchesOtherProfile(10136, 10136, "com.kakao.talk", "com.kakao.talk"));
        assertFalse(TargetIdentity.matchesOtherProfile(10136, 15010373, "com.kakao.talk", "com.discord"));
        assertFalse(TargetIdentity.matchesOtherProfile(10136, 15010136, "com.kakao.talk", ""));
        assertFalse(TargetIdentity.matchesOtherProfile(10136, 15010136, "com.kakao.talk", null));
    }
}
