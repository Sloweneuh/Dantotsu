package ani.dantotsu.connections.github

import ani.dantotsu.settings.Developer

class Contributors {

    fun getContributors(): Array<Developer> {
        // This list shows the original developer and maintainer of this version
        // For more contributors, visit: https://github.com/rebelonion/Dantotsu
        val developers = mutableListOf<Developer>()

        developers.add(
            Developer(
                name = "rebelonion",
                pfp = "https://avatars.githubusercontent.com/u/87634197?v=4",
                role = "Original Developer",
                url = "https://github.com/rebelonion"
            )
        )

        developers.add(
            Developer(
                name = "Sloweneuh",
                pfp = "https://avatars.githubusercontent.com/u/59141955?v=4",
                role = "Maintainer of this version",
                url = "https://github.com/Sloweneuh"
            )
        )

        return developers.toTypedArray()
    }
}