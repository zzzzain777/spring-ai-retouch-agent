package io.github.zzzzain777.retouch.selection;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.NullNode;

import io.github.zzzzain777.retouch.auth.CurrentUser;
import io.github.zzzzain777.retouch.selection.SelectionService.SelectIn;
import io.github.zzzzain777.retouch.selection.SelectionService.SelectionOut;
import io.github.zzzzain777.retouch.session.SessionService;

/** 选区接口，路径与语义沿用上游：没有选区时 GET 返回 JSON null。 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/selection")
class SelectionController {

    private final SelectionService selections;
    private final SessionService sessions;

    SelectionController(SelectionService selections, SessionService sessions) {
        this.selections = selections;
        this.sessions = sessions;
    }

    /** 进入点选前预热。分割模型接入前什么也不做，只校验会话。 */
    @PostMapping("/prepare")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void prepare(CurrentUser user, @PathVariable UUID sessionId) {
        sessions.get(user.id(), sessionId);
    }

    @PostMapping
    SelectionOut select(CurrentUser user, @PathVariable UUID sessionId, @RequestBody SelectIn in) {
        return selections.select(user.id(), sessionId, in);
    }

    @GetMapping
    ResponseEntity<Object> current(CurrentUser user, @PathVariable UUID sessionId) {
        return ResponseEntity.ok(selections.current(user.id(), sessionId).<Object>map(out -> out)
                .orElse(NullNode.getInstance()));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clear(CurrentUser user, @PathVariable UUID sessionId) {
        selections.clear(user.id(), sessionId);
    }
}
