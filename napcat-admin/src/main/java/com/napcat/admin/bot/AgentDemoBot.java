package com.napcat.admin.bot;

import com.napcat.agent.agent.AgentConfig;
import com.napcat.agent.agent.NapCatAgent;
import com.napcat.agent.memory.MemoryExtractor;
import com.napcat.agent.session.Session;
import com.napcat.agent.session.SessionKey;
import com.napcat.agent.session.SessionManager;
import com.napcat.core.annotation.MentionFilter;
import com.napcat.core.annotation.OnGroupMessage;
import com.napcat.core.annotation.OnPrivateMessage;
import com.napcat.core.annotation.WakeFilter;
import com.napcat.core.config.BotProperties;
import com.napcat.core.event.GroupMessageEvent;
import com.napcat.core.message.MessageChain;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class AgentDemoBot {

    @Autowired(required = false)
    @Lazy
    private NapCatAgent agent;

    @Autowired(required = false)
    private SessionManager sessionManager;

    @Autowired(required = false)
    private MemoryExtractor memoryExtractor;

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
        String prompt = event.getMessage().toAgentPrompt();
        if (tryClearSession(event, prompt)) return;

        AgentConfig config = AgentConfig.builder()
                .showToolProcess(true)
                .ackCallback(() -> event.reply(MessageChain.ofFace(277)))  // 👍 表示收到
                .build();

        agent.chat(event.getUserId(), event.getGroupId(), prompt, config,
                        event::reply)
                .thenAccept(event::reply);
    }
    @OnGroupMessage
    @OnPrivateMessage
    @WakeFilter
    public void notAt(GroupMessageEvent event) {
        if (agent == null) return;

        String prompt = event.getMessage().toAgentPrompt();

        // 剔除唤醒关键词
        String cleanedText = removeWakeWords(prompt, keyboards);
        if (tryClearSession(event, cleanedText)) return;

        AgentConfig config = AgentConfig.builder()
                .showToolProcess(true)
                .ackCallback(() -> event.reply(MessageChain.ofFace(277)))  // 👍 表示收到
                .build();

        agent.chat(event.getUserId(), event.getGroupId(), cleanedText, config,
                        event::reply)
                .thenAccept(event::reply);
    }

    /**
     * 检测是否为会话清空命令（/new 或 /clear），如果是则提取记忆后清空并返回 true。
     */
    private boolean tryClearSession(GroupMessageEvent event, String text) {
        if (sessionManager == null) return false;
        String trimmed = text.trim();
        if ("/new".equals(trimmed) || "/clear".equals(trimmed)) {
            SessionKey key = new SessionKey(event.getUserId(), event.getGroupId());
            // 清除前提取记忆
            if (memoryExtractor != null) {
                Session session = sessionManager.get(key);
                if (session != null && !session.getHistory().isEmpty()) {
                    memoryExtractor.extractAndPersistSync(key, session);
                }
            }
            sessionManager.getAndRemove(key);
            event.reply("会话已重置");
            return true;
        }
        return false;
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