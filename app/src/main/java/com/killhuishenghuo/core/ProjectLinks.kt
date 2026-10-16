package com.killhuishenghuo.core

/**
 * The project's public addresses.
 *
 * Here rather than inline at each use because three places need to agree on
 * them: the "about" section links to the repository, the feedback section links
 * to its issue tracker, and both have to keep pointing at the same project. A
 * URL typed twice is a URL that drifts once.
 *
 * The owner is not configurable and deliberately not derived from anything in
 * the build: if this app is forked, the person forking should change this line
 * rather than discover that their fork still reports bugs to the original.
 */
object ProjectLinks {

    /** The repository. Also what the "star this project" row opens. */
    const val REPOSITORY = "https://github.com/PanSomeone/798QuSiBa"

    /**
     * Where a report goes.
     *
     * `/issues/new` rather than `/issues`: opening the list asks the reader to
     * find the button first, and half of them will instead read three closed
     * issues and give up. This lands on the form.
     */
    const val NEW_ISSUE = "$REPOSITORY/issues/new"

    /**
     * What the app tells the user to paste into that form.
     *
     * There is no server behind this project and no way to send anything from
     * the app itself, so the honest instruction is "put it on the clipboard,
     * then paste it where reports go".
     */
    const val FEEDBACK_HINT = "报告已复制，粘贴到 GitHub issue 里即可"
}
