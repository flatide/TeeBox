package com.propertee.tests;

import com.propertee.teebox.CommandGuard;
import com.propertee.teebox.CommandGuardException;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class CommandGuardTest {

    // ---- Block mode: destructive commands should be blocked ----

    @Test(expected = CommandGuardException.class)
    public void shouldBlockRmRfRoot() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("rm -rf /");
    }

    @Test(expected = CommandGuardException.class)
    public void shouldBlockRmRfRootWithTrailingSpace() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("rm -rf / ");
    }

    @Test(expected = CommandGuardException.class)
    public void shouldBlockRmRfEtc() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("rm -rf /etc");
    }

    @Test(expected = CommandGuardException.class)
    public void shouldBlockRmRfVar() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("rm -rf /var/");
    }

    @Test(expected = CommandGuardException.class)
    public void shouldBlockRmRfUsr() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("rm -rf /usr");
    }

    @Test(expected = CommandGuardException.class)
    public void shouldBlockRmFrRoot() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("rm -fr /");
    }

    @Test(expected = CommandGuardException.class)
    public void shouldBlockMkfs() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("mkfs.ext4 /dev/sda1");
    }

    @Test(expected = CommandGuardException.class)
    public void shouldBlockDdToDevice() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("dd if=/dev/zero of=/dev/sda bs=4M");
    }

    @Test(expected = CommandGuardException.class)
    public void shouldBlockShutdown() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("shutdown -h now");
    }

    @Test(expected = CommandGuardException.class)
    public void shouldBlockReboot() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("reboot");
    }

    @Test(expected = CommandGuardException.class)
    public void shouldBlockPoweroff() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("poweroff");
    }

    @Test(expected = CommandGuardException.class)
    public void shouldBlockHalt() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("halt");
    }

    @Test(expected = CommandGuardException.class)
    public void shouldBlockForkBomb() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate(":(){ :|:& };:");
    }

    // ---- Safe commands should NOT be blocked ----

    @Test
    public void shouldAllowRmRfTmpBuild() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("rm -rf /tmp/build");
    }

    @Test
    public void shouldAllowDdToFile() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("dd if=input.bin of=/tmp/output.bin bs=1M");
    }

    @Test
    public void shouldAllowEchoReboot() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("echo reboot scheduled for later");
    }

    @Test
    public void shouldAllowSleep() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("sleep 5");
    }

    @Test
    public void shouldAllowEcho() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("echo hello world");
    }

    @Test
    public void shouldAllowLs() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("ls -la /tmp");
    }

    @Test
    public void shouldAllowRmSingleFile() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("rm /tmp/test.txt");
    }

    @Test
    public void shouldAllowRmRfSubdir() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate("rm -rf /home/user/project/build");
    }

    // ---- Warn mode: should not throw ----

    @Test
    public void warnModeShouldNotThrow() {
        CommandGuard guard = CommandGuard.fromConfig("warn", null, null);
        guard.validate("rm -rf /");
        guard.validate("shutdown -h now");
        guard.validate("mkfs.ext4 /dev/sda1");
    }

    // ---- Off mode: should not throw ----

    @Test
    public void offModeShouldNotThrow() {
        CommandGuard guard = CommandGuard.fromConfig("off", null, null);
        guard.validate("rm -rf /");
        guard.validate("shutdown -h now");
    }

    // ---- Default mode is block ----

    @Test(expected = CommandGuardException.class)
    public void defaultModeIsBlock() {
        CommandGuard guard = CommandGuard.fromConfig(null, null, null);
        guard.validate("rm -rf /");
    }

    // ---- Extra patterns ----

    @Test(expected = CommandGuardException.class)
    public void shouldBlockExtraPattern() {
        CommandGuard guard = CommandGuard.fromConfig("block", "\\bdangerous_cmd\\b", null);
        guard.validate("dangerous_cmd --force");
    }

    @Test
    public void extraPatternsShouldNotAffectDefaults() {
        CommandGuard guard = CommandGuard.fromConfig("block", "\\bdangerous_cmd\\b", null);
        guard.validate("echo safe");
    }

    @Test(expected = CommandGuardException.class)
    public void extraPatternsAddToDefaults() {
        CommandGuard guard = CommandGuard.fromConfig("block", "\\bdangerous_cmd\\b", null);
        // default patterns still work
        guard.validate("rm -rf /etc");
    }

    @Test(expected = CommandGuardException.class)
    public void multipleExtraPatterns() {
        CommandGuard guard = CommandGuard.fromConfig("block", "\\bfoo\\b,\\bbar\\b", null);
        guard.validate("bar --delete");
    }

    // ---- Patterns file replaces defaults ----

    @Test
    public void patternsFileShouldReplaceDefaults() throws Exception {
        File tmpDir = Files.createTempDirectory("guard-test").toFile();
        File patternsFile = new File(tmpDir, "patterns.txt");
        writeFile(patternsFile, "# comment\n\\bcustom_block\\b\n");

        CommandGuard guard = CommandGuard.fromConfig("block", null, patternsFile.getAbsolutePath());

        // custom pattern blocks
        try {
            guard.validate("custom_block now");
            Assert.fail("Expected CommandGuardException");
        } catch (CommandGuardException e) {
            // expected
        }

        // default pattern no longer blocks (replaced by file)
        guard.validate("rm -rf /");
    }

    @Test(expected = CommandGuardException.class)
    public void patternsFileWithExtraPatterns() throws Exception {
        File tmpDir = Files.createTempDirectory("guard-test-extra").toFile();
        File patternsFile = new File(tmpDir, "patterns.txt");
        writeFile(patternsFile, "\\bbase_block\\b\n");

        CommandGuard guard = CommandGuard.fromConfig("block", "\\bextra_block\\b", patternsFile.getAbsolutePath());
        guard.validate("extra_block now");
    }

    // ---- Null command should not throw ----

    @Test
    public void nullCommandShouldNotThrow() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        guard.validate(null);
    }

    // ---- Invalid patterns should be skipped ----

    @Test
    public void invalidPatternShouldBeSkipped() {
        CommandGuard guard = CommandGuard.fromConfig("block", "[invalid(", null);
        // should still work with default patterns
        try {
            guard.validate("rm -rf /");
            Assert.fail("Expected CommandGuardException");
        } catch (CommandGuardException e) {
            // expected
        }
    }

    // ---- Exception carries info ----

    @Test
    public void exceptionShouldCarryCommandAndPattern() {
        CommandGuard guard = CommandGuard.fromConfig("block", null, null);
        try {
            guard.validate("rm -rf /etc");
            Assert.fail("Expected CommandGuardException");
        } catch (CommandGuardException e) {
            Assert.assertEquals("rm -rf /etc", e.getCommand());
            Assert.assertNotNull(e.getMatchedPattern());
            Assert.assertTrue(e.getMessage().contains("rm -rf /etc"));
        }
    }

    // ---- Missing patterns file should throw IllegalArgumentException ----

    @Test(expected = IllegalArgumentException.class)
    public void missingPatternsFileShouldThrow() {
        CommandGuard.fromConfig("block", null, "/nonexistent/patterns.txt");
    }

    private static void writeFile(File file, String content) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }
}
