package com.flatide.teebox;

import org.junit.Assert;
import org.junit.Test;

/**
 * A failed thread in a run's thread list always says why. A Java Error out of a multi worker — a
 * host function whose class could not be initialized (THUMBNAIL on a server started with a DISPLAY
 * it could not reach) — is that thread's error, and some Java Errors carry no message at all.
 */
public class ThreadErrorMessageTest {

    @Test
    public void anErrorWithAMessageShowsIt() {
        Assert.assertEquals("Could not initialize class java.awt.GraphicsEnvironment$LocalGE",
            RunManager.threadErrorMessage(
                new NoClassDefFoundError("Could not initialize class java.awt.GraphicsEnvironment$LocalGE")));
    }

    @Test
    public void anErrorWithoutAMessageShowsItsType() {
        Assert.assertEquals("StackOverflowError", RunManager.threadErrorMessage(new StackOverflowError()));
    }
}
