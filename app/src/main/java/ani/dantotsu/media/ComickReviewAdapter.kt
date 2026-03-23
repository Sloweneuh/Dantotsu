package ani.dantotsu.media

import android.content.Intent
import android.view.View
import androidx.core.content.ContextCompat
import ani.dantotsu.R
import ani.dantotsu.connections.comick.ComickReview
import ani.dantotsu.databinding.ItemReviewsBinding
import ani.dantotsu.loadImage
import eu.kanade.tachiyomi.util.lang.Hash
import ani.dantotsu.profile.activity.ActivityItemBuilder
import com.xwray.groupie.viewbinding.BindableItem

class ComickReviewAdapter(private val review: ComickReview) : BindableItem<ItemReviewsBinding>() {
    private lateinit var binding: ItemReviewsBinding

    override fun bind(viewBinding: ItemReviewsBinding, position: Int) {
        binding = viewBinding
        binding.reviewUserName.text = review.username ?: "Anonymous"
        val email = review.email ?: ""
        val hash = if (email.isBlank()) "" else Hash.md5(email.trim().lowercase())
        val gravatar = if (hash.isBlank()) "https://www.gravatar.com/avatar/?d=identicon" else "https://www.gravatar.com/avatar/$hash?s=80&d=identicon"
        binding.reviewUserAvatar.loadImage(gravatar)
        binding.reviewText.text = review.content ?: ""
        binding.reviewPostTime.text = review.createdAt?.let { ActivityItemBuilder.getDateTime(it) } ?: ""
        binding.reviewTag.text = review.rating?.let { "[${it}]" } ?: ""

        // Hide voting controls for Comick reviews
        binding.reviewUpVote.visibility = View.GONE
        binding.reviewDownVote.visibility = View.GONE
        binding.reviewTotalVotes.visibility = View.GONE

        // Open full Comick review on click
        binding.root.setOnClickListener {
            val intent = Intent(binding.root.context, ComickReviewViewActivity::class.java)
            intent.putExtra("comick_review", review)
            ContextCompat.startActivity(binding.root.context, intent, null)
        }
    }

    override fun getLayout(): Int = R.layout.item_reviews

    override fun initializeViewBinding(view: View): ItemReviewsBinding = ItemReviewsBinding.bind(view)
}
