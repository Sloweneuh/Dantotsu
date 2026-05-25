package ani.dantotsu.media

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
import ani.dantotsu.others.MALReview
import ani.dantotsu.profile.activity.ActivityItemBuilder
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.AniMarkdown

class MALReviewViewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReviewViewBinding

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

        val review = intent.getSerializableExtra("mal_review") as MALReview

        binding.userName.text = review.username ?: getString(R.string.unknown)
        binding.userAvatar.loadImage(review.avatarUrl ?: "")
        binding.userTime.text = review.dateUnix?.let { ActivityItemBuilder.getDateTime(it) } ?: ""

        binding.userContainer.setOnClickListener { }

        binding.profileUserBio.settings.loadWithOverviewMode = true
        binding.profileUserBio.settings.useWideViewPort = true
        binding.profileUserBio.setInitialScale(1)
        val styledHtml = AniMarkdown.getFullAniHTML(
            review.review ?: "",
            ContextCompat.getColor(this, R.color.bg_opp)
        )
        binding.profileUserBio.loadDataWithBaseURL(null, styledHtml, "text/html", "utf-8", null)
        binding.profileUserBio.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
        binding.profileUserBio.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        binding.upvote.visibility = View.GONE
        binding.downvote.visibility = View.GONE
        binding.voteCount.visibility = View.GONE
        binding.voteText.visibility = View.GONE

        try {
            binding.closeButton.setOnClickListener { finish() }
        } catch (_: Throwable) {}
    }
}
