"""Service container for the meal-plan app.

Deliberately thin: the AI is a *client* of this app (through the agent
client / MCP tool surface), not a service inside it — suggestions, grocery
lists and prep schedules arrive through declared actions like any other
client's writes.
"""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class Services:
    features: set[str] = field(default_factory=set)
