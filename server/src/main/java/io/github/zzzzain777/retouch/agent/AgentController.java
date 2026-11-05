package io.github.zzzzain777.retouch.agent;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.github.zzzzain777.retouch.agent.AgentViews.MessageIn;
import io.github.zzzzain777.retouch.agent.AgentViews.TurnOut;
import io.github.zzzzain777.retouch.auth.CurrentUser;
import io.github.zzzzain777.retouch.common.ApiException;

/** 对话指令接口，路径与状态码沿用上游；新增 Idempotency-Key 请求头与轮次轨迹查询。 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/messages")
class AgentController {

    static final int MAX_MESSAGE = 1000;
    static final int MAX_KEY = 64;

    private final AgentService agent;
    private final JsonMapper json;

    AgentController(AgentService agent, JsonMapper json) {
        this.agent = agent;
        this.json = json;
    }

    @GetMapping
    List<TurnOut> list(CurrentUser user, @PathVariable UUID sessionId) {
        return agent.turns(user.id(), sessionId).stream().map(this::out).toList();
    }

    /** 201 返回本轮计划。带同一 Idempotency-Key 的重复请求返回同一轮，并带上 Idempotent-Replayed: true。 */
    @PostMapping
    ResponseEntity<TurnOut> send(CurrentUser user, @PathVariable UUID sessionId, @RequestBody MessageIn in,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        String text = in == null || in.text() == null ? "" : in.text().strip();
        if (text.isEmpty()) {
            throw ApiException.unprocessable("text：指令不能为空");
        }
        if (text.codePointCount(0, text.length()) > MAX_MESSAGE) {
            throw ApiException.unprocessable("text：指令不能超过 " + MAX_MESSAGE + " 字");
        }
        if (key != null && (key.isBlank() || key.length() > MAX_KEY)) {
            throw ApiException.unprocessable("Idempotency-Key：长度需在 1 到 " + MAX_KEY + " 之间");
        }
        var outcome = agent.respond(user.id(), sessionId, text, key);
        var response = ResponseEntity.status(HttpStatus.CREATED);
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(out(outcome.turn()));
    }

    @PostMapping("/{turnId}/confirm")
    TurnOut confirm(CurrentUser user, @PathVariable UUID sessionId, @PathVariable UUID turnId) {
        return out(agent.confirm(user.id(), sessionId, turnId));
    }

    @PostMapping("/{turnId}/cancel")
    TurnOut cancel(CurrentUser user, @PathVariable UUID sessionId, @PathVariable UUID turnId) {
        return out(agent.cancel(user.id(), sessionId, turnId));
    }

    @PostMapping("/{turnId}/retry")
    TurnOut retry(CurrentUser user, @PathVariable UUID sessionId, @PathVariable UUID turnId) {
        return out(agent.retry(user.id(), sessionId, turnId));
    }

    /** 规划轨迹（新增）：模型名、耗时、token 用量、模型原样给出的工具调用与被拒原因。 */
    @GetMapping("/{turnId}/trace")
    JsonNode trace(CurrentUser user, @PathVariable UUID sessionId, @PathVariable UUID turnId) {
        return json.readTree(agent.turn(user.id(), sessionId, turnId).getTrace());
    }

    private TurnOut out(AgentTurn turn) {
        return TurnOut.of(turn, agent.steps(turn));
    }
}
