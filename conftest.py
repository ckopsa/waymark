"""Root conftest.

The waymark7 framework test suite (``tests/waymark7/``) is self-contained:
each module builds its own ``Engine`` over a throwaway set of resources via
``waymark7.testing.per_worker_dsn``, so nothing is needed here.

The ``--waymark7`` conformance suite walks whatever resources an application
enrolls; it expects that application's root conftest to supply a
``waymark7_engine`` async fixture (see ``waymark7.testing.pytest_plugin``).
On this branch there is no application, so the enrollment registry is empty
and ``pytest --waymark7`` collects nothing. Each dogfood app lives on its own
branch and adds its ``waymark7_engine`` fixture here.
"""
from __future__ import annotations
