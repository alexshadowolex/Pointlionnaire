package commands

import Command
import TwitchBotConfig
import handler.IntervalHandler
import handler.QuestionHandler
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.seconds

val questionCommand: Command = Command(
    names = listOf("question", "q"),
    handler = {
        val currentTimeLeft = IntervalHandler.instance.timestampNextAction.value?.minus(Clock.System.now())
        chat.sendMessage(
            TwitchBotConfig.channel,
            "Question is: ${
                QuestionHandler.instance.currentQuestion.value?.questionText.let {
                    "${it ?: TwitchBotConfig.noQuestionPendingText}. " +
                            if (it == null) {
                                "Time until next question comes up: "
                            } else {
                                "Time until answer duration is over: "
                            }
                }
            }" +
                    if (currentTimeLeft == null || currentTimeLeft.inWholeMilliseconds < 0) {
                        "No Timer Running"
                    } else {
                        currentTimeLeft.inWholeSeconds.seconds
                    }
        )
    }
)