package com.napcat.admin.bot;

import com.napcat.agent.agent.AgentConfig;
import com.napcat.agent.agent.NapCatAgent;
import com.napcat.core.annotation.MentionFilter;
import com.napcat.core.annotation.OnGroupMessage;
import com.napcat.core.annotation.OnPrivateMessage;
import com.napcat.core.annotation.WakeFilter;
import com.napcat.core.config.BotProperties;
import com.napcat.core.event.GroupMessageEvent;
import com.napcat.core.message.MessageChain;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AgentDemoBot {

    @Autowired(required = false)
    @Lazy
    private NapCatAgent agent;

    @Autowired
    private BotProperties botProperties;

    private static  List<String> keyboards;

    @PostConstruct
    public void init() {
        if (agent == null || botProperties.getWakeWords().isEmpty()){
            keyboards= new ArrayList<>();
        }else {
            keyboards = botProperties.getWakeWords();
        }
    }

    @OnGroupMessage
    @OnPrivateMessage
    @MentionFilter
    public void onAt(GroupMessageEvent event) {
        if (agent == null) return;
        String plainText = event.getMessage().toPlainText();

        AgentConfig config = AgentConfig.builder()
                .showToolProcess(true)
                .ackCallback(() -> event.reply(MessageChain.ofFace(277)))  // 👍 表示收到
                .build();

        agent.chat(event.getUserId(), event.getGroupId(), plainText, config,
                        event::reply)
                .thenAccept(event::reply);
    }
    @OnGroupMessage
    @OnPrivateMessage
    @WakeFilter
    public void notAt(GroupMessageEvent event) {
        if (agent == null) return;
        String plainText = event.getMessage().toPlainText();

        // 剔除唤醒关键词
        String cleanedText = removeWakeWords(plainText, keyboards);

        AgentConfig config = AgentConfig.builder()
                .showToolProcess(true)
                .ackCallback(() -> event.reply(MessageChain.ofFace(277)))  // 👍 表示收到
                .build();

        agent.chat(event.getUserId(), event.getGroupId(), cleanedText, config,
                        event::reply)
                .thenAccept(event::reply);
    }

    /**
     * 从消息文本中剔除唤醒关键词
     * @param text 原始消息文本
     * @param wakeWords 唤醒词列表
     * @return 剔除唤醒词后的文本
     */
    private String removeWakeWords(String text, List<String> wakeWords) {
        if (text == null || text.isEmpty() || wakeWords == null || wakeWords.isEmpty()) {
            return text;
        }

        String result = text;
        for (String wakeWord : wakeWords) {
            if (wakeWord != null && !wakeWord.isEmpty()) {
                // 忽略大小写替换
                result = result.replaceAll("(?i)" + java.util.regex.Pattern.quote(wakeWord), "");
            }
        }

        // 清理多余的空格
        result = result.replaceAll("\\s+", " ").trim();

        return result;
    }
}