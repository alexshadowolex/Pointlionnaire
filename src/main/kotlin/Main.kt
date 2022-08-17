import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.github.philippheuer.credentialmanager.domain.OAuth2Credential
import com.github.twitch4j.TwitchClient
import com.github.twitch4j.TwitchClientBuilder
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.common.enums.CommandPermission
import commands.helpCommand
import commands.redeemCommand
import handler.QuestionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.*
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import javax.swing.JOptionPane
import kotlin.system.exitProcess
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration


val logger: Logger = LoggerFactory.getLogger("Bot")
val backgroundCoroutineScope = CoroutineScope(Dispatchers.IO)

val json = Json {
    prettyPrint = true
}

var intervalRunning = mutableStateOf(false)

suspend fun main() = try {
    setupLogging()
    val twitchClient = setupTwitchBot()
    if(!intervalHandler(twitchClient)){
        JOptionPane.showMessageDialog(null, "Questions are not properly setup. Check the log for more infos!", "InfoBox: File Debugger", JOptionPane.INFORMATION_MESSAGE)
        logger.error("Questions are not properly setup. Check the log for more infos!")
        exitProcess(0)
    }

    application {
        DisposableEffect(Unit) {
            onDispose {
                twitchClient.chat.sendMessage(TwitchBotConfig.channel, "Bot shutting down ${TwitchBotConfig.leaveEmote}")
                logger.info("App shutting down...")
            }
        }

        Window(
            state = WindowState(size = DpSize(500.dp, 250.dp)),
            title = "Pointlionnaire",
            onCloseRequest = ::exitApplication,
            icon = painterResource("icon.ico"),
            resizable = false
        ) {
            App()
        }
    }
} catch (e: Throwable) {
    JOptionPane.showMessageDialog(null, e.message + "\n" + StringWriter().also { e.printStackTrace(PrintWriter(it)) }, "InfoBox: File Debugger", JOptionPane.INFORMATION_MESSAGE)
    logger.error("Error while executing program.", e)
    exitProcess(0)
}

private const val LOG_DIRECTORY = "logs"

fun setupLogging() {
    Files.createDirectories(Paths.get(LOG_DIRECTORY))

    val logFileName = DateTimeFormatterBuilder()
        .appendInstant(0)
        .toFormatter()
        .format(Instant.now())
        .replace(':', '-')

    val logFile = Paths.get(LOG_DIRECTORY, "${logFileName}.log").toFile().also {
        if (!it.exists()) {
            it.createNewFile()
        }
    }

    System.setOut(PrintStream(MultiOutputStream(System.out, FileOutputStream(logFile))))

    logger.info("Log file '${logFile.name}' has been created.")
}


private suspend fun setupTwitchBot(): TwitchClient {
    val chatAccountToken = File("data/twitchtoken.txt").readText()

    val twitchClient = TwitchClientBuilder.builder()
        .withEnableHelix(true)
        .withEnableChat(true)
        .withChatAccount(OAuth2Credential("twitch", chatAccountToken))
        .build()

    val nextAllowedCommandUsageInstantPerUser = mutableMapOf<Pair<Command, /* user: */ String>, Instant>()

    twitchClient.chat.run {
        connect()
        joinChannel(TwitchBotConfig.channel)
        sendMessage(TwitchBotConfig.channel, "Bot running ${TwitchBotConfig.arriveEmote}")
    }

    twitchClient.eventManager.onEvent(ChannelMessageEvent::class.java) { messageEvent ->
        val message = messageEvent.message
        if (!message.startsWith(TwitchBotConfig.commandPrefix)) {
            return@onEvent
        }

        val parts = message.substringAfter(TwitchBotConfig.commandPrefix).split(" ")
        val command = commands.find { parts.first().lowercase() in it.names } ?: return@onEvent

        if (TwitchBotConfig.onlyMods && CommandPermission.MODERATOR !in messageEvent.permissions) {
            twitchClient.chat.sendMessage(
                TwitchBotConfig.channel,
                "You do not have the required permissions to use this command."
            )
            logger.info("User '${messageEvent.user.name}' does not have the necessary permissions to call command '${command.names.first()}'")

            return@onEvent
        }

        logger.info("User '${messageEvent.user.name}' tried using command '${command.names.first()}' with arguments: ${parts.drop(1).joinToString()}")

        val nextAllowedCommandUsageInstant = nextAllowedCommandUsageInstantPerUser.getOrPut(command to messageEvent.user.name) {
            Instant.now()
        }

        if (Instant.now().isBefore(nextAllowedCommandUsageInstant) && CommandPermission.MODERATOR !in messageEvent.permissions) {
            val secondsUntilTimeoutOver = Duration.between(Instant.now(), nextAllowedCommandUsageInstant).seconds

            twitchClient.chat.sendMessage(
                TwitchBotConfig.channel,
                "${messageEvent.user.name}, You are still on cooldown. Please try again in $secondsUntilTimeoutOver seconds."
            )
            logger.info("Unable to execute command due to ongoing user cooldown.")

            return@onEvent
        }

        val commandHandlerScope = CommandHandlerScope(
            chat = twitchClient.chat,
            user = messageEvent.user
        )

        backgroundCoroutineScope.launch {
            command.handler(commandHandlerScope, parts.drop(1))

            val key = command to messageEvent.user.name
            nextAllowedCommandUsageInstantPerUser[key] = nextAllowedCommandUsageInstantPerUser[key]!!.plus(commandHandlerScope.addedUserCooldown.toJavaDuration())
        }
    }

    logger.info("Twitch client started.")
    return twitchClient
}

fun startOrStopInterval(){
    intervalRunning.value = !intervalRunning.value
    logger.info("intervalRunning: ${intervalRunning.value}")
}
fun intervalHandler(twitchClient: TwitchClient): Boolean {
    val chat = twitchClient.chat
    val questionHandlerInstance = QuestionHandler.instance ?: run {
        return false
    }
    val durationUntilNextQuestion = TwitchBotConfig.totalIntervalDuration / TwitchBotConfig.amountQuestions - TwitchBotConfig.answerDuration
    backgroundCoroutineScope.launch {
        while (true){
            // TODO: Comment in the delays
            if(intervalRunning.value){
                logger.info("Interval running. Amount of asked questions: ${questionHandlerInstance.askedQuestions.size}")
                if(questionHandlerInstance.askedQuestions.isEmpty()){
                    // Starting the interval
                    chat.sendMessage(TwitchBotConfig.channel,
                        "${TwitchBotConfig.attentionEmote} Attention, Attention ${TwitchBotConfig.attentionEmote} The Questions are about to begin! " +
                                "You wanna know, how to participate? ${TwitchBotConfig.amountQuestions} Questions will be asked during the next ${TwitchBotConfig.totalIntervalDuration} and you have per question ${TwitchBotConfig.answerDuration} to answer! ${TwitchBotConfig.explanationEmote}"
                    )

                    //delay(10.seconds)

                    chat.sendMessage(TwitchBotConfig.channel,
                        "You will have ${QuestionHandler.instance.maxAmountTries} tries to get the answer right. Wanna know, how to answer? Type \"${TwitchBotConfig.commandPrefix}${helpCommand.names.first()}\" to see all commands!"
                    )

                    //delay(10.seconds)

                    chat.sendMessage(TwitchBotConfig.channel,
                        "The winner will be announced at the end. They can get a random price by typing \"${TwitchBotConfig.commandPrefix}${redeemCommand.names.first()}\". How cool is that?! ${TwitchBotConfig.ggEmote}"
                    )

                    //delay(30.seconds)
                }

                chat.sendMessage(TwitchBotConfig.channel, "Tighten your seatbelts, the question is coming up!")
                //delay(10.seconds)
                chat.sendMessage(TwitchBotConfig.channel, questionHandlerInstance.popRandomQuestion().also { logger.info("Current question: ${it.questionText} | Current answer: ${it.answer}") }.questionText)

                delay(TwitchBotConfig.answerDuration)
                chat.sendMessage(TwitchBotConfig.channel, "The time is up! ${TwitchBotConfig.timeUpEmote} Next question will be in $durationUntilNextQuestion")
                logger.info("Answer duration is over. Resetting question")
                questionHandlerInstance.resetCurrentQuestion()

                delay(durationUntilNextQuestion)
            }
        }
    }

    return true
}