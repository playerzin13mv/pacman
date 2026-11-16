# Pac-Man (Android nativo)

Jogo feito em **Kotlin** com Canvas do Android, sem navegador, sem WebView e sem nenhuma biblioteca externa.
O labirinto, o Pac-Man, os fantasmas e as frutas vêm da sprite sheet (`app/src/main/assets`).
Os sons são os efeitos originais do Pac-Man (`app/src/main/assets/sounds`).

## Como jogar

- **Toque** na tela inicial para começar.
- **Arraste o dedo** em qualquer lugar da tela para mudar a direção (dá para encadear vários arrastes sem soltar).
- Toque na **engrenagem** (canto superior direito) para abrir as **configurações**: controle (arrastar ou D-Pad), lado do D-Pad, velocidade dos fantasmas (lento, normal, rápido) e som ligado/desligado. As escolhas ficam salvas.
- Coma todas as bolinhas, fuja dos fantasmas e use as pílulas grandes para caçá-los.
- Ganha vida extra com 10.000 pontos. O recorde fica salvo no celular.

## Como gerar o APK (Termux + GitHub)

1. Extraia o `.zip` com o ZArchiver e entre na pasta do projeto no Termux.
2. Copie a pasta para dentro do Termux (`cp -r ~/storage/downloads/pacman ~/pacman`) e envie com `git`.
3. No GitHub, aba **Actions** > **Build APK**. Quando terminar, o APK aparece em **Releases** (`PacMan.apk`) e também como artefato.
4. Baixe o `PacMan.apk`, instale e jogue.

O workflow usa Gradle 8.9 + JDK 17, então não precisa de `gradlew`.

## Estrutura

- `Maze.kt` — mapa de tiles 28x31 (tirado do labirinto da sprite sheet) e busca de caminhos.
- `Game.kt` — regras: Pac-Man, 4 fantasmas (Blinky, Pinky, Inky e Clyde, cada um com a sua IA), modos scatter/chase/assustado, frutas, níveis, vidas, pontuação.
- `GameView.kt` — desenho em tela lógica 224x288 (como o arcade) escalada sem suavização, e controle por arraste.
- `Sfx.kt` — efeitos sonoros e sirenes sintetizados.
- `MainActivity.kt` — tela cheia, retrato, tela sempre ligada.
