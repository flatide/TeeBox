package com.propertee.tests;

import com.propertee.teebox.CommandGuard;
import com.propertee.teebox.CommandGuardException;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

public class CommandGuardTest {

    // ---- Bare commands should be blocked ----

    @Test(expected = CommandGuardException.class)
    public void shouldBlockBareRm() {
        new CommandGuard().validate("rm -rf /tmp/build", null);
    }

    @Test(expected = CommandGuardException.class)
    public void shouldBlockBareEcho() {
        new CommandGuard().validate("echo hello", null);
    }

    @Test(expected = CommandGuardException.class)
    public void shouldBlockBareSleep() {
        new CommandGuard().validate("sleep 5", null);
    }

    @Test(expected = CommandGuardException.class)
    public void shouldBlockBarePython() {
        new CommandGuard().validate("python3 script.py", null);
    }

    @Test(expected = CommandGuardException.class)
    public void shouldBlockBareShutdown() {
        new CommandGuard().validate("shutdown -h now", null);
    }

    // ---- Shell operators should be blocked ----

    @Test
    public void shouldBlockSemicolon() throws Exception {
        File script = createScript("semi");
        assertShellOperatorBlocked(script.getAbsolutePath() + "; rm -rf /");
    }

    @Test
    public void shouldBlockPipe() throws Exception {
        File script = createScript("pipe");
        assertShellOperatorBlocked(script.getAbsolutePath() + " | cat");
    }

    @Test
    public void shouldBlockAmpersand() throws Exception {
        File script = createScript("amp");
        assertShellOperatorBlocked(script.getAbsolutePath() + " && echo done");
    }

    @Test
    public void shouldBlockRedirect() throws Exception {
        File script = createScript("redir");
        assertShellOperatorBlocked(script.getAbsolutePath() + " > /tmp/out");
    }

    @Test
    public void shouldBlockCommandSubstitution() throws Exception {
        File script = createScript("sub");
        assertShellOperatorBlocked(script.getAbsolutePath() + " $(whoami)");
    }

    @Test
    public void shouldBlockBacktick() throws Exception {
        File script = createScript("bt");
        assertShellOperatorBlocked(script.getAbsolutePath() + " `whoami`");
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

    // ---- .sh extension enforcement ----

    @Test
    public void shouldBlockNonShExtension() throws Exception {
        File tmpDir = Files.createTempDirectory("guard-ext").toFile();
        File script = new File(tmpDir, "run.py");
        writeFile(script, "#!/usr/bin/env python3\nprint('hello')");
        try {
            new CommandGuard().validate(script.getAbsolutePath(), null);
            Assert.fail("Expected CommandGuardException");
        } catch (CommandGuardException e) {
            Assert.assertTrue(e.getMatchedPattern().startsWith("not-shell-script:"));
        }
    }

    @Test
    public void shouldBlockNoExtension() throws Exception {
        File tmpDir = Files.createTempDirectory("guard-noext").toFile();
        File script = new File(tmpDir, "run_script");
        writeFile(script, "#!/bin/sh\necho ok");
        try {
            new CommandGuard().validate(script.getAbsolutePath(), null);
            Assert.fail("Expected CommandGuardException");
        } catch (CommandGuardException e) {
            Assert.assertTrue(e.getMatchedPattern().startsWith("not-shell-script:"));
        }
    }

    // ---- Allowed roots enforcement ----

    @Test
    public void shouldAllowScriptWithinAllowedRoot() throws Exception {
        File rootDir = Files.createTempDirectory("guard-root").toFile();
        File script = new File(rootDir, "ok.sh");
        writeFile(script, "#!/bin/sh\necho ok");
        CommandGuard guard = new CommandGuard(Collections.singletonList(rootDir));
        guard.validate(script.getAbsolutePath(), null);
    }

    @Test
    public void shouldBlockScriptOutsideAllowedRoot() throws Exception {
        File rootDir = Files.createTempDirectory("guard-root-allowed").toFile();
        File otherDir = Files.createTempDirectory("guard-root-other").toFile();
        File script = new File(otherDir, "bad.sh");
        writeFile(script, "#!/bin/sh\necho bad");
        CommandGuard guard = new CommandGuard(Collections.singletonList(rootDir));
        try {
            guard.validate(script.getAbsolutePath(), null);
            Assert.fail("Expected CommandGuardException");
        } catch (CommandGuardException e) {
            Assert.assertTrue(e.getMatchedPattern().startsWith("outside-allowed-root:"));
        }
    }

    @Test
    public void shouldAllowScriptInSubdirectoryOfRoot() throws Exception {
        File rootDir = Files.createTempDirectory("guard-root-sub").toFile();
        File subDir = new File(rootDir, "scripts");
        subDir.mkdirs();
        File script = new File(subDir, "nested.sh");
        writeFile(script, "#!/bin/sh\necho nested");
        CommandGuard guard = new CommandGuard(Collections.singletonList(rootDir));
        guard.validate(script.getAbsolutePath(), null);
    }

    @Test
    public void shouldAllowScriptInAnyOfMultipleRoots() throws Exception {
        File root1 = Files.createTempDirectory("guard-root-multi1").toFile();
        File root2 = Files.createTempDirectory("guard-root-multi2").toFile();
        File script = new File(root2, "multi.sh");
        writeFile(script, "#!/bin/sh\necho multi");
        CommandGuard guard = new CommandGuard(Arrays.asList(root1, root2));
        guard.validate(script.getAbsolutePath(), null);
    }

    @Test
    public void noArgConstructorShouldNotEnforceRoots() throws Exception {
        File script = createScript("noroot");
        new CommandGuard().validate(script.getAbsolutePath(), null);
    }

    // ---- cwd validation ----

    @Test
    public void shouldAllowCwdWithinAllowedRoot() throws Exception {
        File rootDir = Files.createTempDirectory("guard-cwd-ok").toFile();
        File subDir = new File(rootDir, "work");
        subDir.mkdirs();
        CommandGuard guard = new CommandGuard(Collections.singletonList(rootDir));
        guard.validateCwd(subDir.getAbsolutePath());
    }

    @Test
    public void shouldBlockCwdOutsideAllowedRoot() throws Exception {
        File rootDir = Files.createTempDirectory("guard-cwd-root").toFile();
        File otherDir = Files.createTempDirectory("guard-cwd-other").toFile();
        CommandGuard guard = new CommandGuard(Collections.singletonList(rootDir));
        try {
            guard.validateCwd(otherDir.getAbsolutePath());
            Assert.fail("Expected CommandGuardException");
        } catch (CommandGuardException e) {
            Assert.assertTrue(e.getMatchedPattern().startsWith("cwd-outside-allowed-root:"));
        }
    }

    @Test
    public void shouldSkipCwdValidationWithoutRoots() throws Exception {
        File otherDir = Files.createTempDirectory("guard-cwd-norestrict").toFile();
        new CommandGuard().validateCwd(otherDir.getAbsolutePath());
    }

    @Test
    public void shouldSkipCwdValidationForNullCwd() throws Exception {
        File rootDir = Files.createTempDirectory("guard-cwd-null").toFile();
        CommandGuard guard = new CommandGuard(Collections.singletonList(rootDir));
        guard.validateCwd(null);
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
            new CommandGuard().validate("rm -rf /etc", null);
            Assert.fail("Expected CommandGuardException");
        } catch (CommandGuardException e) {
            Assert.assertEquals("rm -rf /etc", e.getCommand());
            Assert.assertTrue(e.getMatchedPattern().startsWith("bare-command:"));
        }
    }

    // ---- Helpers ----

    private static void assertShellOperatorBlocked(String command) {
        try {
            new CommandGuard().validate(command, null);
            Assert.fail("Expected CommandGuardException for: " + command);
        } catch (CommandGuardException e) {
            Assert.assertTrue(e.getMatchedPattern().startsWith("shell-operator:"));
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
