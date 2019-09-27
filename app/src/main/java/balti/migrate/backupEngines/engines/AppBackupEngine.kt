package balti.migrate.backupEngines.engines

import balti.migrate.R
import balti.migrate.backupEngines.BackupServiceKotlin
import balti.migrate.backupEngines.ParentBackupClass
import balti.migrate.backupEngines.containers.BackupIntentData
import balti.migrate.backupEngines.utils.BackupUtils
import balti.migrate.extraBackupsActivity.apps.containers.AppBatch
import balti.migrate.utilities.CommonToolKotlin.Companion.ERR_APP_BACKUP_SHELL
import balti.migrate.utilities.CommonToolKotlin.Companion.ERR_APP_BACKUP_SUPPRESSED
import balti.migrate.utilities.CommonToolKotlin.Companion.ERR_APP_BACKUP_TRY_CATCH
import balti.migrate.utilities.CommonToolKotlin.Companion.ERR_SCRIPT_MAKING_TRY_CATCH
import balti.migrate.utilities.CommonToolKotlin.Companion.EXTRA_PROGRESS_TYPE_APP_PROGRESS
import balti.migrate.utilities.CommonToolKotlin.Companion.EXTRA_PROGRESS_TYPE_MAKING_APP_SCRIPTS
import balti.migrate.utilities.CommonToolKotlin.Companion.FILE_PREFIX_BACKUP_SCRIPT
import balti.migrate.utilities.CommonToolKotlin.Companion.FILE_PREFIX_RETRY_SCRIPT
import balti.migrate.utilities.CommonToolKotlin.Companion.MIGRATE_STATUS
import balti.migrate.utilities.CommonToolKotlin.Companion.PREF_NEW_ICON_METHOD
import balti.migrate.utilities.IconTools
import java.io.*

class AppBackupEngine(private val jobcode: Int, private val bd: BackupIntentData,
                               private val appBatch: AppBatch,
                               private val doBackupInstallers : Boolean,
                               private val busyboxBinaryPath: String) : ParentBackupClass(bd, "") {

    companion object {
        var ICON_STRING = ""
    }

    private var BACKUP_PID = -999

    private val pm by lazy { engineContext.packageManager }
    private val backupUtils by lazy { BackupUtils() }
    private val iconTools by lazy { IconTools() }
    private var suProcess : Process? = null

    private val allErrors by lazy { ArrayList<String>(0) }
    private val actualErrors by lazy { ArrayList<String>(0) }

    private fun writeFileList(fileName: String, appName: String){
        writeToFileList("$fileName\n")
        broadcastProgress(appName, fileName, false)
    }

    init {

        customPreExecuteFunction = {
            if (bd.partNumber == 0){

                var previousBackupScripts = engineContext.filesDir.listFiles {
                    f -> (f.name.startsWith(FILE_PREFIX_BACKUP_SCRIPT) || f.name.startsWith(FILE_PREFIX_RETRY_SCRIPT)) &&
                        f.name.endsWith(".sh")
                }
                for (f in previousBackupScripts) f.delete()

                engineContext.externalCacheDir?.let {
                    previousBackupScripts = it.listFiles {
                        f -> (f.name.startsWith(FILE_PREFIX_BACKUP_SCRIPT) || f.name.startsWith(FILE_PREFIX_RETRY_SCRIPT)) &&
                            f.name.endsWith(".sh")
                    }
                    for (f in previousBackupScripts) f.delete()
                }
            }
        }
    }

    private fun addToActualErrors(err: String){
        actualErrors.add(err)
        allErrors.add(err)
    }

    private fun systemAppInstallScript(sysAppPackageName: String, sysAppPastingDir: String, appDir: String) {

        val scriptName = "$sysAppPackageName.sh"
        val scriptLocation = "$actualDestination/$scriptName"
        val script = File(scriptLocation)

        var pastingDir = "/system"

        sysAppPastingDir.let {fullDir ->

            "/system/".let { if (fullDir.startsWith(it)) pastingDir = fullDir.substring(it.length) }

            "/system_root/".run {
                if (fullDir.startsWith(this)) {
                    pastingDir = fullDir.substring(this.length)

                    "/system_root/system/".run {
                        if (fullDir.startsWith(this)) {
                            pastingDir = fullDir.substring(this.length)

                            "/system_root/system/system".run {
                                if (fullDir.startsWith(this))
                                    pastingDir = fullDir.substring(this.length)
                            }

                        }

                    }
                }
            }
        }

        val scriptText = "#!sbin/sh\n\n" +
                "\n" +
                "SYSTEM=$(cat /tmp/migrate/SYSTEM)\n" +
                "mkdir -p \$SYSTEM/$pastingDir\n" +
                "mv /tmp/$appDir/*.apk \$SYSTEM/$pastingDir/\n" +
                "cd /tmp/" + "\n" +
                "rm -rf " + appDir + "\n" +
                "rm -rf " + scriptName + "\n"


        File(actualDestination).mkdirs()

        commonTools.tryIt {
            val writer = BufferedWriter(FileWriter(script))
            writer.write(scriptText)
            writer.close()
        }

        script.setExecutable(true, false)
    }

    private fun makeBackupScript(): String?{

        try {

            val title = getTitle(R.string.making_app_script)

            resetBroadcast(false, title, EXTRA_PROGRESS_TYPE_MAKING_APP_SCRIPTS)

            val scriptFile = File(engineContext.filesDir, "$FILE_PREFIX_BACKUP_SCRIPT${bd.partNumber}.sh")
            val scriptWriter = BufferedWriter(FileWriter(scriptFile))
            val appAndDataBackupScript = commonTools.unpackAssetToInternal("backup_app_and_data.sh", "backup_app_and_data.sh", false)

            scriptWriter.write("#!sbin/sh\n\n")
            scriptWriter.write("echo \" \"\n")
            scriptWriter.write("sleep 1\n")
            scriptWriter.write("echo \"--- PID: $$\"\n")
            scriptWriter.write("cp ${scriptFile.absolutePath} ${engineContext.externalCacheDir}/\n")
            scriptWriter.write("cp $busyboxBinaryPath $actualDestination/\n")

            appBatch.appPackets.let {packets ->
                for (i in 0 until packets.size) {

                    if (BackupServiceKotlin.cancelAll) break

                    val packet = packets[i]

                    val appName = formatName(pm.getApplicationLabel(packet.PACKAGE_INFO.applicationInfo).toString())
                    val modifiedAppName = "$appName(${i+1}/${packets.size})"

                    broadcastProgress(modifiedAppName, modifiedAppName, true, commonTools.getPercentage(i + 1, packets.size))

                    val packageName = packet.PACKAGE_INFO.packageName

                    var apkPath = "NULL"
                    var apkName = "NULL"       //has .apk extension
                    if (packet.APP) {

                        apkPath = packet.PACKAGE_INFO.applicationInfo.sourceDir
                        apkName = apkPath.substring(apkPath.lastIndexOf('/') + 1)
                        apkPath = apkPath.substring(0, apkPath.lastIndexOf('/'))
                        apkName = commonTools.applyNamingCorrectionForShell(apkName)

                        writeFileList("$packageName.app", modifiedAppName)
                    }

                    var dataPath = "NULL"
                    var dataName = "NULL"
                    if (packet.DATA) {
                        dataPath = packet.PACKAGE_INFO.applicationInfo.dataDir
                        dataName = dataPath.substring(dataPath.lastIndexOf('/') + 1)
                        dataPath = dataPath.substring(0, dataPath.lastIndexOf('/'))

                        writeFileList("$packageName.tar.gz", modifiedAppName)
                    }

                    if (packet.PERMISSION) {
                        backupUtils.makePermissionFile(packageName, actualDestination, pm)
                        writeFileList("$packageName.perm", modifiedAppName)
                    }

                    var versionName: String? = packet.PACKAGE_INFO.versionName
                    versionName = if (versionName == null || versionName == "") "_"
                    else formatName(versionName)

                    val appIcon: String = iconTools.getIconString(packet.PACKAGE_INFO, pm)
                    var appIconFileName: String? = null
                    if (sharedPreferences.getBoolean(PREF_NEW_ICON_METHOD, true)) {
                        appIconFileName = backupUtils.makeIconFile(packageName, appIcon, actualDestination)
                        writeFileList("$appIconFileName", modifiedAppName)
                    }

                    val echoCopyCommand = "echo \"$MIGRATE_STATUS: $modifiedAppName icon: ${if (appIconFileName == null) appIcon else "$packageName.icon"}\"\n"
                    val scriptCommand = "sh $appAndDataBackupScript " +
                            "$packageName $actualDestination " +
                            "$apkPath $apkName " +
                            "$dataPath $dataName " +
                            "$busyboxBinaryPath\n"

                    scriptWriter.write(echoCopyCommand, 0, echoCopyCommand.length)
                    scriptWriter.write(scriptCommand, 0, scriptCommand.length)

                    val isSystem = apkPath.startsWith("/system")
                    if (isSystem) systemAppInstallScript(packageName, apkPath, packageName)

                    backupUtils.makeMetadataFile(
                            isSystem, appName, apkName, "$dataName.tar.gz", appIconFileName,
                            versionName, packet.PERMISSION, packet, bd, doBackupInstallers, actualDestination,
                            if (appIconFileName != null) appIcon else null
                    )

                    writeFileList("$packageName.json", modifiedAppName)
                }

            }

            scriptWriter.write("echo \"--- App files copied ---\"\n")
            scriptWriter.close()

            scriptFile.setExecutable(true)

            return scriptFile.absolutePath
        }
        catch (e: Exception){
            e.printStackTrace()
            addToActualErrors("$ERR_SCRIPT_MAKING_TRY_CATCH${bd.errorTag}: ${e.message}")
            return null
        }
    }

    private fun runBackupScript(scriptFileLocation: String){

        try {

            if (!File(scriptFileLocation).exists())
                throw Exception(engineContext.getString(R.string.script_file_does_not_exist))

            val title = getTitle(R.string.backingUp)

            resetBroadcast(false, title, EXTRA_PROGRESS_TYPE_APP_PROGRESS)

            suProcess = Runtime.getRuntime().exec("su")
            suProcess?.let {
                val suInputStream = BufferedWriter(OutputStreamWriter(it.outputStream))
                val outputStream = BufferedReader(InputStreamReader(it.inputStream))
                val errorStream = BufferedReader(InputStreamReader(it.errorStream))

                suInputStream.write("sh $scriptFileLocation\n")
                suInputStream.write("exit\n")
                suInputStream.flush()

                var c = 0
                var appName = ""
                var progress = 0

                backupUtils.iterateBufferedReader(outputStream, { output ->

                    if (BackupServiceKotlin.cancelAll) {
                        cancelTask(suProcess, BACKUP_PID)
                        return@iterateBufferedReader true
                    }

                    if (output.startsWith("--- PID:")) {
                        commonTools.tryIt {
                            BACKUP_PID = output.substring(output.lastIndexOf(" ") + 1).toInt()
                        }
                    }

                    var line = output

                    if (output.startsWith(MIGRATE_STATUS)) {

                        line = output.substring(MIGRATE_STATUS.length + 2)

                        if (line.contains("icon:")) {
                            ICON_STRING = line.substring(line.lastIndexOf(' ')).trim()
                            line = line.substring(0, line.indexOf("icon:"))
                        }

                        appName = line
                        progress = commonTools.getPercentage(++c, appBatch.appPackets.size)
                        broadcastProgress(appName, appName, true, progress)
                    }
                    else broadcastProgress(appName, line, false)

                    return@iterateBufferedReader line == "--- App files copied ---"
                })

                commonTools.tryIt { it.waitFor() }

                backupUtils.iterateBufferedReader(errorStream, { errorLine ->

                    var ignorable = false

                    (BackupUtils.ignorableWarnings + BackupUtils.correctableErrors).forEach { warnings ->
                        if (errorLine.endsWith(warnings)) ignorable = true
                    }

                    if (!ignorable)
                        addToActualErrors("$ERR_APP_BACKUP_SHELL${bd.errorTag}: $errorLine")
                    else allErrors.add("$ERR_APP_BACKUP_SUPPRESSED${bd.errorTag}: $errorLine")

                    return@iterateBufferedReader false
                })

            }

        }
        catch (e: Exception){
            e.printStackTrace()
            addToActualErrors("$ERR_APP_BACKUP_TRY_CATCH${bd.errorTag}: ${e.message}")
        }
    }

    override fun doInBackground(vararg params: Any?): Any {
        val scriptLocation = makeBackupScript()
        scriptLocation?.let { runBackupScript(it) }
        return 0
    }

    override fun postExecuteFunction() {
        BACKUP_PID = -999
        onBackupComplete.onBackupComplete(jobcode, actualErrors.size == 0, allErrors)
    }
}