"""Entry point for the packaged build.

PyInstaller runs its entry script as a top-level module, so pointing it at
dpx_captions/main.py strips the package context and every relative import
inside it fails. Importing the package from here keeps that context intact.
"""

import sys

from dpx_captions.main import main

if __name__ == "__main__":
    sys.exit(main())
