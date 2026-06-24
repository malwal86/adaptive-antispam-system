"""Make the ml/ modules importable from tests.

The ``ml/*.py`` scripts import each other by bare module name (``from feature_schema
import ...``), which works when a script is run directly because Python puts the
script's own directory on ``sys.path``. Under pytest the test files live in
``ml/tests/``, so this conftest — which pytest imports before collection — puts the
``ml/`` directory on the path so the same bare imports resolve.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
