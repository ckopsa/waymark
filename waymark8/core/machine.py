"""The declared state machine: states, transitions, graph queries.

2.0 addition (design §9): ``path_to`` — the machine computes reachability
paths, so the conformance suite can walk to any state without a hand-written
factory per resource.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from types import MappingProxyType
from typing import Mapping

from .actions import ActionDef


@dataclass(frozen=True)
class StateMachine:
    states: tuple[str, ...]
    initial: str
    terminal: frozenset[str]
    actions: Mapping[str, ActionDef]  # declaration order preserved

    @classmethod
    def build(cls, *, states: tuple[str, ...], initial: str,
              terminal: frozenset[str], actions: dict[str, ActionDef]) -> "StateMachine":
        finished = {
            name: defn.with_terminal(defn.to in terminal)
            for name, defn in actions.items()
        }
        return cls(states=states, initial=initial, terminal=terminal,
                   actions=MappingProxyType(finished))

    def transitions_from(self, state: str) -> list[ActionDef]:
        return [d for d in self.actions.values() if state in d.from_]

    def transitions_not_from(self, state: str) -> list[ActionDef]:
        return [d for d in self.actions.values() if state not in d.from_]

    def reachable_states(self) -> frozenset[str]:
        seen = {self.initial}
        queue = deque([self.initial])
        while queue:
            here = queue.popleft()
            for defn in self.transitions_from(here):
                if defn.to not in seen:
                    seen.add(defn.to)
                    queue.append(defn.to)
        return frozenset(seen)

    def path_to(self, target: str) -> list[ActionDef] | None:
        """Shortest action path ``initial → target`` (BFS over non-bulk,
        state-changing transitions), or None when unreachable. Guards are
        the walker's problem — the graph only promises edges exist."""
        if target == self.initial:
            return []
        prev: dict[str, tuple[str, ActionDef]] = {}
        queue = deque([self.initial])
        while queue:
            here = queue.popleft()
            for defn in self.transitions_from(here):
                if defn.bulk or defn.to == here or defn.to in prev \
                        or defn.to == self.initial:
                    continue
                prev[defn.to] = (here, defn)
                if defn.to == target:
                    path: list[ActionDef] = []
                    at = target
                    while at != self.initial:
                        at, step = prev[at]
                        path.append(step)
                    return list(reversed(path))
                queue.append(defn.to)
        return None

    def reverse_edges(self, defn: ActionDef) -> dict[str, list[ActionDef]]:
        """For each source state of ``defn``, the actions leading back to it
        from ``defn.to`` (self-loops count as trivially reversible)."""
        out: dict[str, list[ActionDef]] = {}
        for src in defn.from_:
            if defn.to == src:
                out[src] = [defn]
                continue
            out[src] = [
                d for d in self.actions.values()
                if defn.to in d.from_ and d.to == src
            ]
        return out
