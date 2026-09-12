import sys

from PySide6.QtGui import QFontDatabase
from PySide6.QtWidgets import QApplication

from .core.fonts import font_files
from .gui.main_window import MainWindow


def main() -> int:
    app = QApplication(sys.argv)

    for path in font_files():
        QFontDatabase.addApplicationFont(str(path))

    window = MainWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
