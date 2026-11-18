package com.emmanuel.tvhome;

import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Reduced motion (config.xml reduced_motion): the one spring and the one time limit every animation uses then. */
final class Motion {

    /** The longest any reduced-motion animation runs, in milliseconds. */
    static final int REDUCED_MS = 90;
    /** Critically damped (no bounce) and stiff enough that any move, even across the screen, settles in 5 frames. */
    static final float REDUCED_STIFFNESS = 15000f;
    /** The spring stops once it is within this many pixels of its target. */
    static final float REDUCED_MIN_CHANGE = 2f;

    private Motion() {
    }

    static void reduce(SpringAnimation spring) {
        spring.setSpring(new SpringForce().setStiffness(REDUCED_STIFFNESS).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        spring.setMinimumVisibleChange(REDUCED_MIN_CHANGE);
    }

    /** Attaches CleanYourTV's focus scale spring physics (SCALE_X / SCALE_Y) to a focusable view. */
    static void attachSpringFocus(View view, boolean animate, float focusedScale) {
        if (view == null || !animate) return;
        SpringAnimation sx = new SpringAnimation(view, DynamicAnimation.SCALE_X, 1f);
        SpringAnimation sy = new SpringAnimation(view, DynamicAnimation.SCALE_Y, 1f);
        sx.setSpring(new SpringForce(1f).setStiffness(420f).setDampingRatio(0.72f));
        sy.setSpring(new SpringForce(1f).setStiffness(420f).setDampingRatio(0.72f));
        view.setOnFocusChangeListener((v, focused) -> {
            float target = focused ? focusedScale : 1f;
            float damping = focused ? 0.68f : 0.88f;
            sx.getSpring().setDampingRatio(damping);
            sy.getSpring().setDampingRatio(damping);
            sx.animateToFinalPosition(target);
            sy.animateToFinalPosition(target);
        });
    }
}
