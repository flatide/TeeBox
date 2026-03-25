package com.propertee.tests;

import com.propertee.teebox.RuntimePolicy;

import org.junit.Assert;
import org.junit.Test;

public class RuntimePolicyTest {

    @Test(expected = IllegalStateException.class)
    public void shouldBlockRootUid() {
        RuntimePolicy.verifyNonRootUid(0);
    }

    @Test
    public void shouldAllowNonRootUid() {
        RuntimePolicy.verifyNonRootUid(1000);
    }

    @Test
    public void shouldParseUid() {
        Assert.assertEquals(Integer.valueOf(1000), RuntimePolicy.parseUid("1000\n"));
    }

    @Test
    public void shouldReturnNullForInvalidUid() {
        Assert.assertNull(RuntimePolicy.parseUid("not-a-number"));
    }
}
