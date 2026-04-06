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

    // ---- Bare commands are allowed except denied commands ----

    @Test
    public void shouldAllowBareRm() {
        new CommandGuard().validate("rm -rf /tmp/build", null);
    }

    @Test
    public void shouldAllowBareEcho() {
        new CommandGuard().validate("echo hello", null);
    }

    @Test
    public void shouldAllowBareSleep() {
        new CommandGuard().validate("sleep 5", null);
    }

    @Test
    public void shouldAllowBarePython() {
        new CommandGuard().validate("python3 script.py", null);
    }

    @Test
    public void shouldBlockBareShutdownCommandString() {
        assertDeniedCommandBlocked("shutdown -h now", "shutdown");
    }

    @Test
    public void shouldBlockBareRebootCommandString() {
        assertDeniedCommandBlocked("reboot", "reboot");
    }

    @Test
    public void shouldBlockDangerousRecursiveRmRoot() {
        assertDangerousRmBlocked("rm -rf /");
    }

    @Test
    public void shouldBlockDangerousRecursiveRmGlob() {
        assertDangerousRmBlocked("rm -rf /*");
    }

    @Test
    public void shouldBlockDangerousRecursiveRmEtcGlob() {
        assertDangerousRmBlocked("rm -rf /etc/*");
    }

    @Test
    public void shouldBlockDangerousRecursiveRmHome() {
        assertDangerousRmBlocked("rm -rf $HOME");
    }

    @Test
    public void shouldAllowNonRecursiveRmEtcFile() {
        new CommandGuard().validate("rm /etc/hosts", null);
    }

    @Test
    public void shouldBlockDangerousDdToDevice() {
        try {
            new CommandGuard().validate("dd if=/dev/zero of=/dev/disk1", null);
            Assert.fail("Expected CommandGuardException");
        } catch (CommandGuardException e) {
            Assert.assertEquals("dangerous-dd-target", e.getMatchedPattern());
        }
    }

    @Test
    public void shouldAllowDdToRegularFile() {
        new CommandGuard().validate("dd if=input.bin of=/tmp/output.bin", null);
    }

    @Test
    public void shouldBlockBareSudo() {
        assertDeniedCommandBlocked("sudo whoami", "sudo");
    }

    @Test
    public void shouldBlockBareSu() {
        assertDeniedCommandBlocked("su root", "su");
    }

    @Test
    public void shouldBlockPathSudo() {
        assertDeniedCommandBlocked("/usr/bin/sudo whoami", "sudo");
    }

    // ---- Shell syntax should be allowed ----

    @Test
    public void shouldAllowSemicolon() throws Exception {
        File script = createScript("semi");
        new CommandGuard().validate(script.getAbsolutePath() + "; echo ok", null);
    }

    @Test
    public void shouldAllowPipe() throws Exception {
        File script = createScript("pipe");
        new CommandGuard().validate(script.getAbsolutePath() + " | cat", null);
    }

    @Test
    public void shouldAllowAmpersand() throws Exception {
        File script = createScript("amp");
        new CommandGuard().validate(script.getAbsolutePath() + " && echo done", null);
    }

    @Test
    public void shouldAllowRedirect() throws Exception {
        File script = createScript("redir");
        new CommandGuard().validate(script.getAbsolutePath() + " > /tmp/out", null);
    }

    @Test
    public void shouldAllowShellWrapperPayload() {
        new CommandGuard().validate("sh -c 'sleep 1; echo done'", null);
    }

    @Test
    public void shouldBlockSudoInsideShellWrapperPayload() {
        assertDeniedCommandBlocked("sh -c 'sudo whoami'", "sudo");
    }

    @Test
    public void shouldBlockDangerousRmInsideShellWrapperPayload() {
        assertDangerousRmBlocked("sh -c 'rm -rf /etc/*'");
    }

    @Test
    public void shouldBlockDeniedCommandInCompoundSequence() {
        assertDeniedCommandBlocked("echo ok; sudo whoami", "sudo");
    }

    @Test
    public void shouldAllowPathExecutableAfterEnvAssignment() throws Exception {
        File script = createScript("envpath");
        new CommandGuard().validate("DEMO=1 " + script.getAbsolutePath() + " arg1", null);
    }

    // ---- Control characters should be blocked ----

    @Test
    public void shouldBlockNewline() throws Exception {
        File script = createScript("nl");
        assertControlCharBlocked(script.getAbsolutePath() + "\nrm -rf /", "0x0a");
    }

    @Test
    public void shouldBlockCarriageReturn() throws Exception {
        File script = createScript("cr");
        assertControlCharBlocked(script.getAbsolutePath() + "\rrm -rf /", "0x0d");
    }

    @Test
    public void shouldBlockNullByte() throws Exception {
        File script = createScript("nul");
        assertControlCharBlocked(script.getAbsolutePath() + "\0rm -rf /", "0x00");
    }

    // ---- File path executables ----

    @Test
    public void shouldAllowNonShExtension() throws Exception {
        File tmpDir = Files.createTempDirectory("guard-ext").toFile();
        File script = new File(tmpDir, "run.py");
        writeFile(script, "#!/usr/bin/env python3\nprint('hello')");
        new CommandGuard().validate(script.getAbsolutePath(), null);
    }

    @Test
    public void shouldAllowNoExtension() throws Exception {
        File tmpDir = Files.createTempDirectory("guard-noext").toFile();
        File script = new File(tmpDir, "run_script");
        writeFile(script, "#!/bin/sh\necho ok");
        new CommandGuard().validate(script.getAbsolutePath(), null);
    }

    @Test
    public void shouldAllowPathExecutableWithoutAllowedRootsPolicy() throws Exception {
        File script = createScript("noroot");
        new CommandGuard().validate(script.getAbsolutePath(), null);
    }

    // ---- Operators inside quotes should be allowed ----

    @Test
    public void shouldAllowOperatorsInDoubleQuotes() throws Exception {
        File script = createScript("dq");
        new CommandGuard().validate(script.getAbsolutePath() + " \"arg;with|operators\"", null);
    }

    @Test
    public void shouldAllowOperatorsInSingleQuotes() throws Exception {
        File script = createScript("sq");
        new CommandGuard().validate(script.getAbsolutePath() + " 'arg;with|operators'", null);
    }

    // ---- Script file paths should be allowed ----

    @Test
    public void shouldAllowAbsoluteScriptPath() throws Exception {
        File script = createScript("abs");
        new CommandGuard().validate(script.getAbsolutePath(), null);
    }

    @Test
    public void shouldAllowRelativeScriptWithCwd() throws Exception {
        File tmpDir = Files.createTempDirectory("guard-rel").toFile();
        writeFile(new File(tmpDir, "run.sh"), "#!/bin/sh\necho ok\n");
        new CommandGuard().validate("./run.sh", tmpDir.getAbsolutePath());
    }

    @Test
    public void shouldAllowScriptWithArgs() throws Exception {
        File script = createScript("args");
        new CommandGuard().validate(script.getAbsolutePath() + " arg1 arg2 --flag", null);
    }

    // ---- Missing script file should be blocked ----

    @Test(expected = CommandGuardException.class)
    public void shouldBlockMissingFile() {
        new CommandGuard().validate("/nonexistent/script.sh", null);
    }

    @Test(expected = CommandGuardException.class)
    public void shouldBlockMissingRelativeFile() throws Exception {
        File tmpDir = Files.createTempDirectory("guard-miss").toFile();
        new CommandGuard().validate("./missing.sh", tmpDir.getAbsolutePath());
    }

    // ---- Null command should not throw ----

    @Test
    public void nullCommandShouldNotThrow() {
        new CommandGuard().validate(null, null);
    }

    // ---- Exception carries info ----

    @Test
    public void exceptionShouldCarryCommandAndPattern() {
        try {
            new CommandGuard().validate("sudo whoami", null);
            Assert.fail("Expected CommandGuardException");
        } catch (CommandGuardException e) {
            Assert.assertEquals("sudo whoami", e.getCommand());
            Assert.assertEquals("denied-command:sudo", e.getMatchedPattern());
        }
    }

    // ---- Helpers ----

    private static void assertDangerousRmBlocked(String command) {
        try {
            new CommandGuard().validate(command, null);
            Assert.fail("Expected CommandGuardException for dangerous rm");
        } catch (CommandGuardException e) {
            Assert.assertEquals("dangerous-rm-target", e.getMatchedPattern());
        }
    }

    private static void assertControlCharBlocked(String command, String expectedHex) {
        try {
            new CommandGuard().validate(command, null);
            Assert.fail("Expected CommandGuardException for control char");
        } catch (CommandGuardException e) {
            Assert.assertTrue("Expected control-char pattern but got: " + e.getMatchedPattern(),
                    e.getMatchedPattern().startsWith("control-char:"));
            Assert.assertTrue("Expected hex " + expectedHex + " but got: " + e.getMatchedPattern(),
                    e.getMatchedPattern().contains(expectedHex));
        }
    }

    private static void assertDeniedCommandBlocked(String command, String expectedCommand) {
        try {
            new CommandGuard().validate(command, null);
            Assert.fail("Expected CommandGuardException for denied command");
        } catch (CommandGuardException e) {
            Assert.assertEquals("denied-command:" + expectedCommand, e.getMatchedPattern());
        }
    }

    private static File createScript(String name) throws Exception {
        File tmpDir = Files.createTempDirectory("guard-" + name).toFile();
        File script = new File(tmpDir, name + ".sh");
        writeFile(script, "#!/bin/sh\necho ok\n");
        return script;
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
