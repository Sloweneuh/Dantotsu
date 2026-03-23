package ani.dantotsu.media

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityReviewViewBinding
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.profile.ProfileActivity
import ani.dantotsu.profile.activity.ActivityItemBuilder
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.AniMarkdown

class ComickReviewViewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReviewViewBinding
    private lateinit var review: ani.dantotsu.connections.comick.ComickReview

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityReviewViewBinding.inflate(layoutInflater)
        binding.userContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        binding.reviewContent.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarHeight
        }
        setContentView(binding.root)

        review = intent.getSerializableExtra("comick_review") as ani.dantotsu.connections.comick.ComickReview

        binding.userName.text = review.username ?: getString(R.string.unknown)
        binding.userAvatar.loadImage(review.email?.let { hashEmailToGravatar(it) } ?: "")
        binding.userTime.text = review.createdAt?.let { ActivityItemBuilder.getDateTime(it) } ?: ""

        binding.userContainer.setOnClickListener {
            // Comick users are not linked to local profiles; nothing to open.
            if (!review.username.isNullOrBlank()) {
                // No local profile id — open a search or ignore
            }
        }

        binding.profileUserBio.settings.loadWithOverviewMode = true
        binding.profileUserBio.settings.useWideViewPort = true
        binding.profileUserBio.setInitialScale(1)
        val styledHtml = AniMarkdown.getFullAniHTML(
            review.content ?: "",
            ContextCompat.getColor(this, R.color.bg_opp)
        )
        binding.profileUserBio.loadDataWithBaseURL(null, styledHtml, "text/html", "utf-8", null)
        binding.profileUserBio.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
        binding.profileUserBio.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Hide vote controls for Comick reviews
        binding.upvote.visibility = View.GONE
        binding.downvote.visibility = View.GONE
        binding.voteCount.visibility = View.GONE
        binding.voteText.visibility = View.GONE

        // Close/back button
        try {
            binding.closeButton.setOnClickListener { finish() }
        } catch (_: Throwable) {}
    }

    private fun hashEmailToGravatar(email: String): String {
        val e = email.trim().lowercase()
        val hash = try { eu.kanade.tachiyomi.util.lang.Hash.md5(e) } catch (_: Exception) { "" }
        return if (hash.isBlank()) "https://www.gravatar.com/avatar/?d=identicon" else "https://www.gravatar.com/avatar/$hash?s=200&d=identicon"
    }
}
