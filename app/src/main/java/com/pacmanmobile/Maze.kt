package com.pacmanmobile

/**
 * Mapa de tiles 28x31 extraído do labirinto da sprite sheet.
 * '#' parede, '.' bolinha, 'o' pílula de poder, ' ' corredor vazio,
 * 'D' porta da casa dos fantasmas, 'H' interior da casa.
 */
object Maze {
    const val W = 28
    const val H = 31
    const val TUNNEL_ROW = 14
    const val INF = 9999

    val ROWS = arrayOf(
        "############################",
        "#............##............#",
        "#.####.#####.##.#####.####.#",
        "#o####.#####.##.#####.####o#",
        "#.####.#####.##.#####.####.#",
        "#..........................#",
        "#.####.##.########.##.####.#",
        "#.####.##.########.##.####.#",
        "#......##....##....##......#",
        "######.##### ## #####.######",
        "######.##### ## #####.######",
        "######.##          ##.######",
        "######.## ###DD### ##.######",
        "######.## #HHHHHH# ##.######",
        "      .   #HHHHHH#   .      ",
        "######.## #HHHHHH# ##.######",
        "######.## ######## ##.######",
        "######.##          ##.######",
        "######.## ######## ##.######",
        "######.## ######## ##.######",
        "#............##............#",
        "#.####.#####.##.#####.####.#",
        "#.####.#####.##.#####.####.#",
        "#o..##.......  .......##..o#",
        "###.##.##.########.##.##.###",
        "###.##.##.########.##.##.###",
        "#......##....##....##......#",
        "#.##########.##.##########.#",
        "#.##########.##.##########.#",
        "#..........................#",
        "############################"
    )

    fun at(x: Int, y: Int): Char {
        if (y == TUNNEL_ROW && (x < 0 || x >= W)) return ' '
        if (x < 0 || x >= W || y < 0 || y >= H) return '#'
        return ROWS[y][x]
    }

    /** Onde o Pac-Man e os fantasmas "normais" podem andar. */
    fun open(x: Int, y: Int): Boolean {
        val c = at(x, y)
        return c == ' ' || c == '.' || c == 'o'
    }

    fun isTunnel(tx: Int, ty: Int): Boolean = ty == TUNNEL_ROW && (tx <= 5 || tx >= 22)

    /** Campo de distâncias (BFS) incluindo porta e casa dos fantasmas. */
    fun bfs(src: List<Pair<Int, Int>>): Array<IntArray> {
        val d = Array(H) { IntArray(W) { INF } }
        val q = ArrayDeque<Int>()
        for (p in src) {
            d[p.second][p.first] = 0
            q.addLast(p.second * W + p.first)
        }
        while (q.isNotEmpty()) {
            val v = q.removeFirst()
            val x = v % W
            val y = v / W
            for (k in 0..3) {
                val nx = x + Dir.DX[k]
                val ny = y + Dir.DY[k]
                if (nx < 0 || nx >= W || ny < 0 || ny >= H) continue
                if (ROWS[ny][nx] == '#') continue
                if (d[ny][nx] > d[y][x] + 1) {
                    d[ny][nx] = d[y][x] + 1
                    q.addLast(ny * W + nx)
                }
            }
        }
        return d
    }
}
