package com.peakload.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peakload.admission.LoadPolicy.Band;
import com.peakload.admission.LoadPolicy.LoadLevel;
import com.peakload.admission.LoadPolicy.State;
import org.junit.jupiter.api.Test;

/** Pure policy tests: no Spring, no clock, no metrics. The thresholds are the test's own, not the config defaults. */
class LoadPolicyTest {

    // In-flight: ELEVATED 0.5/0.4, HIGH 0.75/0.6, HARD_CAP 0.95/0.85. Latency: 250/150, 1000/600, 2500/1500 ms.
    private final LoadPolicy policy = new LoadPolicy(
            new Band(0.50, 0.40, 250, 150),
            new Band(0.75, 0.60, 1000, 600),
            new Band(0.95, 0.85, 2500, 1500));

    @Test
    void eachLevelShedsTheRightClasses() {
        // NORMAL: nothing is shed.
        for (RequestClass c : RequestClass.values()) {
            assertThat(LoadPolicy.sheds(LoadLevel.NORMAL, c)).as("NORMAL sheds " + c).isFalse();
        }
        // ELEVATED: LOW first, nothing else.
        assertThat(LoadPolicy.sheds(LoadLevel.ELEVATED, RequestClass.LOW)).isTrue();
        assertThat(LoadPolicy.sheds(LoadLevel.ELEVATED, RequestClass.STANDARD)).isFalse();
        assertThat(LoadPolicy.sheds(LoadLevel.ELEVATED, RequestClass.CRITICAL)).isFalse();
        // HIGH: LOW and STANDARD, but CRITICAL is still served.
        assertThat(LoadPolicy.sheds(LoadLevel.HIGH, RequestClass.LOW)).isTrue();
        assertThat(LoadPolicy.sheds(LoadLevel.HIGH, RequestClass.STANDARD)).isTrue();
        assertThat(LoadPolicy.sheds(LoadLevel.HIGH, RequestClass.CRITICAL)).isFalse();
    }

    @Test
    void hardCapRejectsCriticalToo() {
        for (RequestClass c : RequestClass.values()) {
            assertThat(LoadPolicy.sheds(LoadLevel.HARD_CAP, c)).as("HARD_CAP sheds " + c).isTrue();
        }
    }

    @Test
    void inFlightFractionEntersEachLevel() {
        assertThat(policy.step(State.START, 0.30, 0).level()).isEqualTo(LoadLevel.NORMAL);
        assertThat(policy.step(State.START, 0.60, 0).level()).isEqualTo(LoadLevel.ELEVATED);
        assertThat(policy.step(State.START, 0.80, 0).level()).isEqualTo(LoadLevel.HIGH);
        assertThat(policy.step(State.START, 0.96, 0).level()).isEqualTo(LoadLevel.HARD_CAP);
    }

    @Test
    void latencyEntersEachLevelOnItsOwn() {
        assertThat(policy.step(State.START, 0, 100).level()).isEqualTo(LoadLevel.NORMAL);
        assertThat(policy.step(State.START, 0, 300).level()).isEqualTo(LoadLevel.ELEVATED);
        assertThat(policy.step(State.START, 0, 1200).level()).isEqualTo(LoadLevel.HIGH);
        assertThat(policy.step(State.START, 0, 3000).level()).isEqualTo(LoadLevel.HARD_CAP);
    }

    @Test
    void overallLevelIsTheHigherOfTheTwoSignals() {
        State state = policy.step(State.START, 0.80, 300); // in-flight HIGH, latency ELEVATED
        assertThat(state.byInFlight()).isEqualTo(LoadLevel.HIGH);
        assertThat(state.byLatency()).isEqualTo(LoadLevel.ELEVATED);
        assertThat(state.level()).isEqualTo(LoadLevel.HIGH);
    }

    @Test
    void aLevelIsHeldUntilTheSignalFallsBelowItsExitThreshold() {
        State elevated = policy.step(State.START, 0.55, 0);
        assertThat(elevated.level()).isEqualTo(LoadLevel.ELEVATED);

        // Between exit (0.40) and enter (0.50): the level is kept.
        assertThat(policy.step(elevated, 0.45, 0).level()).isEqualTo(LoadLevel.ELEVATED);
        // Below the exit threshold: the level drops.
        assertThat(policy.step(elevated, 0.35, 0).level()).isEqualTo(LoadLevel.NORMAL);
    }

    @Test
    void aBandBetweenExitAndEnterDoesNotEnterTheLevel() {
        // Starting from NORMAL, 0.45 is below the enter threshold (0.50), so it stays NORMAL.
        assertThat(policy.step(State.START, 0.45, 0).level()).isEqualTo(LoadLevel.NORMAL);
    }

    @Test
    void noFlappingWhileTheSignalOscillatesAroundAThreshold() {
        // Enter at 0.55, then oscillate between 0.41 and 0.49, which is inside the hysteresis band.
        State state = policy.step(State.START, 0.55, 0);
        for (int i = 0; i < 100; i++) {
            double fraction = (i % 2 == 0) ? 0.41 : 0.49;
            state = policy.step(state, fraction, 0);
            assertThat(state.level()).as("step " + i).isEqualTo(LoadLevel.ELEVATED);
        }
    }

    @Test
    void hardCapStepsDownThroughHighWhenTheSignalFalls() {
        State state = policy.step(State.START, 0.96, 0);
        assertThat(state.level()).isEqualTo(LoadLevel.HARD_CAP);

        // Below the hard-cap exit (0.85) but above the HIGH exit (0.60): lands on HIGH, not NORMAL.
        state = policy.step(state, 0.84, 0);
        assertThat(state.level()).isEqualTo(LoadLevel.HIGH);

        state = policy.step(state, 0.59, 0);
        assertThat(state.level()).isEqualTo(LoadLevel.ELEVATED);
    }

    @Test
    void bandRejectsExitNotBelowEnter() {
        assertThatThrownBy(() -> new Band(0.5, 0.5, 250, 150)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Band(0.5, 0.4, 150, 250)).isInstanceOf(IllegalStateException.class);
    }
}
