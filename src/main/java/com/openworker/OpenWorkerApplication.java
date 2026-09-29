package com.openworker;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {
        org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration.class,
        org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration.class,
        org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration.class,
        org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration.class
})
@EnableScheduling
public class OpenWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenWorkerApplication.class, args);
    }

    @Bean
    @Primary
    public ChatClient.Builder primaryChatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
