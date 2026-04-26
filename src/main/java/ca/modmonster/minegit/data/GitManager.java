package ca.modmonster.minegit.data;

import net.minecraft.client.Minecraft;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import ca.modmonster.minegit.MineGIT;

public class GitManager {
    public static boolean syncEnabled(Minecraft minecraft, String worldId) {
        return syncEnabled(getPath(minecraft, worldId));
    }

    public static boolean syncEnabled(Path worldFolder) {
        return worldFolder.resolve(".git").toFile().exists();
    }

    /**
     * Pull a world's commits from remote. Will never merge, only fast-forward
     * @param minecraft Minecraft client instance
     * @param worldId ID of the world (world folder name)
     * @param progressMonitor ProgressMonitor which will be updated during the pull
     * @return 0 if successful, 1 if generic error, 2 if network error
     */
    public static int pull(Minecraft minecraft, String worldId, ProgressMonitor progressMonitor) {
        Path worldFolder = getPath(minecraft, worldId);
        Config config = ConfigManager.getCurrentConfig();
        try (Git git = Git.open(worldFolder.toFile())) {
            PullResult result = git.pull()
                    .setRemote("origin")
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(config.username, config.getPat()))
                    .setProgressMonitor(progressMonitor)
                    .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                    .call();

            if (result.isSuccessful()) return 0;

            // pull was unsuccessful, check if it was caused by a recent prune
            progressMonitor.beginTask("Checking for pruning", 0);
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve("HEAD");
            int localCommitTime;
            int remoteCommitTime = -1;

            // get time of most recent local commit
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit commit = walk.parseCommit(head);
                localCommitTime = commit.getCommitTime();
            }

            // get time of oldest remote commit
            BranchTrackingStatus status = BranchTrackingStatus.of(repo, repo.getBranch());
            String remoteBranch = status == null? "refs/remotes/origin/" + repo.getBranch() : status.getRemoteTrackingBranch();

            // Resolve remote branch (e.g. origin/main)
            Ref remoteHead = repo.findRef(remoteBranch);
            ObjectId tip = remoteHead.getObjectId();

            try (RevWalk walk = new RevWalk(repo)) {
                walk.markStart(walk.parseCommit(tip));

                for (RevCommit commit : walk) {
                    if (commit.getParentCount() == 0) {
                        remoteCommitTime = commit.getCommitTime();
                    }
                }
            }

            if (remoteCommitTime == -1) return 1;

            if (remoteCommitTime > localCommitTime) {
                // Prune happened, we can safely force reset to origin
                git.reset()
                        .setMode(ResetCommand.ResetType.HARD)
                        .setRef(remoteHead.getName())
                        .setProgressMonitor(progressMonitor)
                        .call();
                return 0;
            }

            return 1;
        } catch (TransportException e) {
            MineGIT.LOGGER.warn("Network error when pulling from repo");
            return 2;
        } catch (IOException | GitAPIException e) {
            MineGIT.LOGGER.error("Error pulling from repo", e);
            return 1;
        }
    }

    /**
     * @param worldFolder Path to the world's folder
     * @param progressMonitor ProgressMonitor which will be updated during the push
     * @return 0 if successful, 1 if generic error, 2 if network error
     */
    public static int push(Path worldFolder, ProgressMonitor progressMonitor) {
        Config config = ConfigManager.getCurrentConfig();
        try (Git git = Git.open(worldFolder.toFile())) {
            // add all
            progressMonitor.beginTask("Stage world to commit", 0);
            git.add()
                    .addFilepattern(".")
                    .call();
            // commit
            progressMonitor.beginTask("Commit world state", 0);
            String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("h:mm a, MM/dd/yy"));
            git.commit()
                    .setMessage("World snapshot - " + timestamp)
                    .call();
            // push
            git.push()
                    .setRemote("origin")
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(config.username, config.getPat()))
                    .setProgressMonitor(progressMonitor)
                    .call();
            return 0;
        } catch (TransportException e) {
            MineGIT.LOGGER.warn("Network error when pushing to repo");
            return 1;
        } catch (GitAPIException | IOException e) {
            MineGIT.LOGGER.error("Error with Git repo", e);
            return 2;
        }
    }

    public static boolean init(Minecraft minecraft, String worldId, String repoUrl) {
        Path worldFolder = getPath(minecraft, worldId);
        Config config = ConfigManager.getCurrentConfig();
        try (Git git = Git.init().setDirectory(worldFolder.toFile()).call()) {
            // add all
            git.add()
                    .addFilepattern(".")
                    .call();
            // commit
            String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("h:mm a, MM/dd/yy"));
            git.commit()
                    .setMessage("Initial world snapshot - " + timestamp)
                    .call();
            // create branch
            git.checkout()
                    .setName("main")
                    .setCreateBranch(true)
                    .call();
            // add remote
            git.remoteAdd()
                    .setName("origin")
                    .setUri(new URIish(repoUrl))
                    .call();
            // push
            git.push()
                    .setRemote("origin")
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(config.username, config.getPat()))
                    .call();
            return true;
        } catch (GitAPIException | URISyntaxException e) {
            MineGIT.LOGGER.error("Error with Git repo", e);
            return false;
        }
    }

    public static int cloneRepo(Minecraft minecraft, String repo) {
        Config config = ConfigManager.getCurrentConfig();
        String repoUrl = String.format("https://github.com/%s/%s.git", config.username, repo);
        Path localWorldFolder = getPath(minecraft, repo.replaceFirst(Pattern.quote("minegit_"), ""));

        // Add a counter at the end if world folder already exists
        int i = 1;
        while (localWorldFolder.toFile().exists()) {
            localWorldFolder = getPath(minecraft, repo.replaceFirst(Pattern.quote("minegit_"), "") + "_" + i);
            i++;
        }

        try (Git ignored = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(localWorldFolder.toFile())
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(config.username, config.getPat()))
                .setDepth(1)
                .call()) {
            return 0;
        } catch (InvalidRemoteException e) {
            MineGIT.LOGGER.error("Error cloning repo: Invalid remote", e);
            return 1;
        } catch (GitAPIException e) {
            MineGIT.LOGGER.error("Error cloning repo", e);
            return 2;
        }
    }

    private static Path getPath(Minecraft minecraft, String worldId) {
        return minecraft.getLevelSource().getBaseDir().resolve(worldId);
    }

    /**
     * Recursively make the .git folder within the provided world folder writable
     * This prevents issues when needing to upgrade the world from <=1.21.11 to >=26.1
     * @param minecraft Minecraft client reference
     * @param worldId The world ID containing the Git repo
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void makeWritable(Minecraft minecraft, String worldId) {
        Path root = getPath(minecraft, worldId).resolve(".git");
        if (!root.toFile().exists()) return;

        try (Stream<Path> stream = Files.walk(root)) {
            stream.forEach(path -> {
                File file = path.toFile();

                // Windows
                file.setReadable(true, false);
                file.setWritable(true, false);
                file.setExecutable(true, false);

                // Windows DOS attribute (read only flag)
                try {
                    DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class);

                    if (dos != null) {
                        dos.setReadOnly(false);
                    }
                } catch (UnsupportedOperationException | IOException ignored) {}

                // POSIX / Unix (Linux & macOS)
                try {
                    PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
                    if (view != null) {
                        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);

                        perms.add(PosixFilePermission.OWNER_READ);
                        perms.add(PosixFilePermission.OWNER_WRITE);
                        perms.add(PosixFilePermission.OWNER_EXECUTE);

                        Files.setPosixFilePermissions(path, perms);
                    }
                } catch (UnsupportedOperationException | IOException ignored) {}
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Re-hide the .git folder on Windows
        try {
            Files.setAttribute(root, "dos:hidden", true);
        } catch (Exception ignored) {}
    }

    public static boolean prune(Minecraft minecraft, String worldId, ProgressMonitor progressMonitor) {
        progressMonitor.beginTask("Opening world", 0);
        Path worldFolder = getPath(minecraft, worldId);
        Config config = ConfigManager.getCurrentConfig();
        try (Git git = Git.open(worldFolder.toFile())) {
            // get current branch name
            String mainBranch = git.getRepository().getBranch();

            // pull latest changes
            progressMonitor.beginTask("Pulling from GitHub", 0);
            git.pull()
                    .setRemote("origin")
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(config.username, config.getPat()))
                    .setProgressMonitor(progressMonitor)
                    .call();
            progressMonitor.beginTask("Creating temporary branch", 0);
            // new branch
            git.checkout()
                    .setName("prune")
                    .setOrphan(true)
                    .setProgressMonitor(progressMonitor)
                    .call();
            // add all
            progressMonitor.beginTask("Stage world to commit", 0);
            git.add()
                    .addFilepattern(".")
                    .call();
            // commit
            progressMonitor.beginTask("Commit world state", 0);
            String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("h:mm a, MM/dd/yy"));
            git.commit()
                    .setMessage("World pruning - " + timestamp)
                    .call();
            // delete main branch
            git.branchDelete()
                    .setBranchNames(mainBranch)
                    .setForce(true)
                    .setProgressMonitor(progressMonitor)
                    .call();
            progressMonitor.beginTask("Renaming temporary branch to main", 0);
            // rename temp branch to main
            git.branchRename()
                    .setNewName(mainBranch)
                    .setOldName("prune")
                    .call();
            // push
            git.push()
                    .setRemote("origin")
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(config.username, config.getPat()))
                    .setForce(true)
                    .setProgressMonitor(progressMonitor)
                    .call();

            return true;
        } catch (GitAPIException | IOException e) {
            MineGIT.LOGGER.error("Error pruning world! ", e);
            return false;
        }
    }
}
