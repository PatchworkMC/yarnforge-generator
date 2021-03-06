package net.patchworkmc.yarnforge.generator

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.jsoniter.JsonIterator
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.RefNotAdvertisedException
import org.eclipse.jgit.lib.AnyObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.net.URI
import java.net.URL
import com.jsoniter.any.Any as JsonAny

fun main(args: Array<String>) {
	Main().main(args)
}

class Main : CliktCommand() {
	private val startFresh by option(help = "start a completely new repository").flag()

	// using convert as a hack to verify and set before, probably not the best solution but eh
	private val target by option(help = "version to use").convert {
		val to = getVersionGeneric(it)
		val minor = to.split('.')[1].toInt()
		before = "1." + (minor - 1) + ".x"
		return@convert to
	}.required()

	/**
	 * the generic (1.NN.x) version before the [target]
	 */
	private lateinit var before: String
	private val yarnForgeDir by argument().path(mustExist = false, canBeFile = false, canBeDir = true).convert { it.toFile() }

	private val dataDir = File("yarnforge-data")
	private val pluginDir = dataDir.resolve("yarnforge-plugin")

	// data
	private val skippedCommits = ArrayList<RevCommit>()
	private val yarnMeta = JsonIterator.deserialize(URL("https://meta.fabricmc.net/v2/versions/yarn").readBytes())

	override fun run() {
		// Download yarnforge-plugin
		if (!pluginDir.exists()) {
			// https://github.com/ramidzkh/yarnforge-plugin/pull/8
			Git.cloneRepository().setURI("https://github.com/theglitch76/yarnforge-plugin.git").setDirectory(pluginDir).call()
		}
		if (startFresh) {
			startFresh()
		} else {
			resume()
		}
	}

	private fun resume() {
		val git = Git.open(yarnForgeDir)
		val repo = git.repository
		// In case something was aborted halfway through
		git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call()
		// Update our branches

		val branches = git.branchList().call().map { it.name }
		if (!branches.contains("yarn-$target")) {
			git.fetch().setRemote("yarn").call()
			// see if it exists
			if (repo.findRef("refs/remotes/yarn/yarn-$target") != null) {
				git.branchCreate().setForce(true).setName("yarn-$target").setStartPoint("yarn/yarn-$target").call()
			} else {
				if (repo.findRef("refs/remotes/yarn/yarn-$before") != null) {
					// hunt down the start commit
					// TODO: DEDUPLICATE ME!!
					val hash = git.log().addRange(repo.resolve("refs/heads/$before"), repo.resolve("refs/heads/$target")).call().last().getParent(0).name
					val walk = RevWalk(git.repository)
					var candidate = walk.parseCommit(repo.exactRef("refs/heads/yarn-$before").objectId)
					walk.markStart(candidate)
					while (true) {
						if (candidate.fullMessage.substringAfter("Tracking commit: https://github.com/MinecraftForge/MinecraftForge/commit/") == hash) {
							git.branchCreate().setForce(true).setName("yarn-$target").setStartPoint(candidate.name).call()
							break
						} else {
							candidate = walk.next()
						}
					}
				}
			}
		}

		updateBranch("yarn-$target", git)
		// Find the first commit
		git.checkout().setName("yarn-$target").call()
		val startCommit = git.log().add(repo.resolve("HEAD")).setMaxCount(1).call().first()
			.fullMessage.substringAfter("Tracking commit: https://github.com/MinecraftForge/MinecraftForge/commit/")
		// Establish our commits, reverse the list to be chronological
		// TODO: this has weird behavior where history is nonlinear
		//		but nothing has broken yet so it's probably fine
		val commits = git.log().addRange(repo.resolve("refs/heads/$before"), repo.resolve("refs/heads/$target")).call().toCollection(ArrayList())
		commits.reverse()

		for (commit in commits.subList(commits.map { it.name }.indexOf(startCommit) + 1, commits.size)) {
			echo(commit.shortMessage)
			step(git, commit)
			echo("done")
		}
	}

	private fun updateBranch(branch: String, git: Git) {
		val current = git.repository.fullBranch.substringAfter("refs/heads/")
		git.checkout().setName(branch).call()
		try {
			git.pull().setRemote("origin").setFastForward(MergeCommand.FastForwardMode.FF_ONLY).call()
		} catch (ex: RefNotAdvertisedException) {
			//
		}

		git.checkout().setName(current).call()
	}

	private fun startFresh() {
		yarnForgeDir.deleteRecursively()
		val git = Git.cloneRepository().setURI("https://github.com/MinecraftForge/MinecraftForge.git")
			.setDirectory(yarnForgeDir).setBranch(target).call()
		val repo = git.repository
		git.remoteAdd().setName("yarn").setUri(URIish("https://github.com/patchworkmc/yarnforge.git")).call()
		git.branchCreate().setForce(true).setName(before).setStartPoint("origin/$before").call()

		val commits = git.log().addRange(repo.resolve("refs/heads/$before"), repo.resolve("refs/heads/$target")).call().toCollection(ArrayList())
		commits.reverse()

		while (true) {
			val commit = commits[0]
			git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call()
			git.checkout().setName(commit.name).call()

			if (!remap(detectVersion(git.repository, commit))) {
				skippedCommits.add(commit)
				commits.remove(commit)
				continue
			}

			git.add().addFilepattern(".").call()
			git.reset().setRef(commit.getParent(0).name).setMode(ResetCommand.ResetType.SOFT).call()
			git.checkout().setName("yarn-$target").setCreateBranch(true).call()
			git.commit().setSign(false).setMessage(createCommitMessage(commit)).setAuthor(commit.authorIdent).setCommitter(commit.committerIdent).call()
			echo("initial commit done")
			skippedCommits.clear()
			commits.remove(commit)
			break
		}

		for (commit in commits) {
			echo(commit.shortMessage)
			step(git, commit)
			echo("done")
		}

	}

	private fun step(git: Git, commit: RevCommit) {
		var git = git
		val version = detectVersion(git.repository, commit)
		git.checkout().setName(commit.name).call()

		if (!remap(version)) {
			skippedCommits.add(commit)
			git.add().addFilepattern(".").call()
			git.reset().setRef("HEAD").setMode(ResetCommand.ResetType.HARD).call()
			git.checkout().setName("yarn-$target").call()
			return
		}

		git.reset().setRef("HEAD~1").setMode(ResetCommand.ResetType.SOFT).call()
		git.add().addFilepattern(".").call()
		git.stashCreate().call()
		git.checkout().setName("yarn-$target").call()
		git.close()
		// This can't be a jgit command because, despite having the merge strategies, none of the commits support them
		runGitProcess("cherry-pick", "-n", "-m1", "-Xtheirs", "stash")
		// This will output a list of all unmerged paths something like
		// error: path 'example' is unmerged
		val output = String(ProcessBuilder("git", "checkout", "--", ".").directory(yarnForgeDir).redirectErrorStream(true).start().inputStream.readBytes())

		if (output.isNotEmpty()) {
			val split = output.split("'")
			// we only want to capture the part in the quotes, not outside
			for (x in 1 until split.size step 2) {
				// Nuke all unmerged files. Since it's merged with -Xtheirs this ***should*** only happen with a delete, so this resolves conflicts properly
				runGitProcess("rm", "-rf", split[x])
			}
		}

		git = Git.open(yarnForgeDir)
		skippedCommits.clear()
		git.add().addFilepattern(".").call()
		git.commit().setSign(false).setMessage(createCommitMessage(commit)).setAuthor(commit.authorIdent).setCommitter(commit.committerIdent).call()
	}

	private fun remap(version: String): Boolean {
		addYarnForge(yarnForgeDir)
		echo("setup")
		var exitCode = runGradlewProcess("clean", "setup", "--no-daemon")

		if (exitCode != 0) {
			echo("exit code $exitCode, skipping")
			return false
		}

		echo("remap")
		// TODO: do not hardcode!
		exitCode = runGradlewProcess("forgeRemapYarn", "--mc-version", version, "--mappings", getYarnVersion(version), "--stacktrace", "--no-daemon")

		if (exitCode != 0) {
			echo("exit code $exitCode, skipping")
			return false
		}

		val remappedDir = yarnForgeDir.resolve("remapped")
		runGitProcess("rm", "-rf", "src/main/java")
		remappedDir.resolve("main/").copyRecursively(yarnForgeDir.resolve("src/main/java/"))
		try {
			runGitProcess("rm", "-rf", "src/test/java")
			remappedDir.resolve("test/").copyRecursively(yarnForgeDir.resolve("src/test/java/"))
		} catch (ignored: NoSuchFileException) {
			//
		}

		runGitProcess("rm", "-rf", "patches")
		remappedDir.resolve("patches").copyRecursively(yarnForgeDir.resolve("patches/minecraft"))
		remappedDir.deleteRecursively()
		return true
	}

	/**
	 * Returns string in the format net.fabricmc:yarn:1.15.2+build.1
	 */
	private fun getYarnVersion(version: String): String {
		for (any in yarnMeta.asList()) {
			if (any["gameVersion"].asString() == version) {
				return any["maven"].asString()
			}
		}

		throw IllegalArgumentException("No Yarn version exists for $version")
	}

	private fun detectVersion(repository: Repository, commit: RevCommit): String {
		var string = readFile(repository, commit, "build.gradle")
		string = string.substringAfter("MC_VERSION = '")
		string = string.substringBefore("'")
		return string
	}

	private fun getVersionGeneric(version: String): String {
		var raw = version
		// 1.16-rc1
		if (raw.contains('-')) {
			raw = raw.split("-")[0]
		}
		return raw
	}

	private fun addYarnForge(dir: File) {
		run {
			// settings.gradle
			dir.resolve("settings.gradle").appendText("\r\nincludeBuild('" + pluginDir.absolutePath + "')")
			// build.gradle
			var gradle = dir.resolve("build.gradle").readText()
			val repoTarget = "buildscript {\r\n    repositories {\r\n"
			val dependencyTarget = "    dependencies {\r\n"
			val working = repoTarget + "        maven { url = 'https://oss.sonatype.org/content/groups/public/' }" +
					"\r\n        maven { url = 'https://maven.fabricmc.net/' }\r\n" + gradle.substringAfter(repoTarget).substringBefore(dependencyTarget)
			// TODO: Do not hardcode version!
			gradle = working + dependencyTarget + "        classpath 'me.ramidzkh:yarnforge-plugin:1.3.0-SNAPSHOT'\r\n" + gradle.substringAfter(dependencyTarget)
			gradle = "$gradle\r\napply plugin: 'yarnforge-plugin'"
			dir.resolve("build.gradle").writeText(gradle)
		}
		run {
			// for some reason this one instance of declaring a dependency on asm 7 gives stitch a heart attack, so
			// we update it to 9.0
			val buildSrcGradle = dir.resolve("buildSrc/build.gradle")

			if (buildSrcGradle.exists()) {
				val gradle = buildSrcGradle.readLines().toMutableList()
				for (line in gradle) {
					if (line.contains("implementation 'org.ow2.asm")) {
						// TODO: don't hardcode
						gradle[gradle.indexOf(line)] = line.replace("7.2", "9.0")
					}
				}
				val sb = StringBuilder()
				gradle.forEach { sb.append("$it\n") }
				buildSrcGradle.writeText(sb.toString())
			}
		}
	}

	private fun readFile(repository: Repository, commit: RevCommit, path: String): String {
		val objId = TreeWalk.forPath(repository, path, commit.tree).getObjectId(0)
		return String(repository.newObjectReader().open(objId).bytes)
	}

	private fun runGradlewProcess(vararg args: String): Int {
		return runProcess("./gradlew", *args)
	}

	private fun runGitProcess(vararg args: String): Int {
		return runProcess("git", *args)
	}

	private fun runProcess(vararg args: String): Int {
		val process = ProcessBuilder(*args).directory(yarnForgeDir).redirectErrorStream(true).start()
		// if the process' output buffer gets full then it deadlocks
		val bytes = process.inputStream.readBytes()
		val exitCode = process.waitFor()
		// "resolving" merge conflicts is handled, we only care about what happens for gradle processes
		if (exitCode != 0 && args[0] == "./gradlew") {
			File("ERR_" + System.currentTimeMillis() + ".txt").writeBytes(bytes)
		}

		return exitCode
	}

	private fun createCommitMessage(commit: RevCommit): String {
		var commitMessage = commit.fullMessage

		for (skipped in skippedCommits) {
			commitMessage += "\n\nSkipped commit: " + skipped.shortMessage + " https://github.com/MinecraftForge/MinecraftForge/commit/" + skipped.name + "\n"
		}

		commitMessage += "\nTracking commit: https://github.com/MinecraftForge/MinecraftForge/commit/" + commit.name
		return commitMessage
	}
}

fun JsonAny.asString(): String {
	return this.`as`(String::class.java)
}