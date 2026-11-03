package io.github.amarz45.findanything.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * A test double for the library's chat model: records every request and answers with a scripted
 * reply, or throws the scripted error. Library types stay in this package's tests.
 */
final class FakeChatModel implements ChatModel {

    final List<ChatRequest> requests = new ArrayList<>();
    private AiMessage reply = AiMessage.from("OK");
    private RuntimeException failure;

    static FakeChatModel replying(AiMessage reply) {
        FakeChatModel model = new FakeChatModel();
        model.reply = reply;
        return model;
    }

    static FakeChatModel failing(RuntimeException failure) {
        FakeChatModel model = new FakeChatModel();
        model.failure = failure;
        return model;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        requests.add(request);
        if (failure != null) {
            throw failure;
        }
        return ChatResponse.builder().aiMessage(reply).build();
    }

    ChatRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }
}
