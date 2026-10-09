plugins {
    `java-library`
}

// Natives: alles, was direkt das Betriebssystem anspricht – Fenstersysteme (FFM), zweiter KI-Zeiger mit eigener Maus und
// Tastatur (JNA: user32/gdi32/UI Automation, AppKit über die ObjC-Runtime, XInput2/MPX) und Programmstart im Hintergrund.
// Die Desktop-App nutzt nur die Schnittstellen (WindowSystem, CursorProvider/CursorController, ProgramLauncher).
dependencies {
    implementation("net.java.dev.jna:jna-platform:5.18.1")
    // Betriebssystem-Erkennung für die Auswahl des CursorProvider (PlatformEnum)
    api("com.github.oshi:oshi-core:6.11.1")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
