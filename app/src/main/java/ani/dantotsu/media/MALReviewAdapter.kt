package ani.dantotsu.media

import android.content.Intent
import android.view.View
import androidx.core.content.ContextCompat
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemReviewsBinding
import ani.dantotsu.loadImage
import ani.dantotsu.others.MALReview
import ani.dantotsu.profile.activity.ActivityItemBuilder
import com.xwray.groupie.viewbinding.BindableItem

class MALReviewAdapter(private val review: MALReview) : BindableItem<ItemReviewsBinding>() {
    private lateinit var binding: ItemReviewsBinding

    override fun bind(viewBinding: ItemReviewsBinding, position: Int) {
        binding = viewBinding
        binding.reviewUserName.text = review.username ?: "Anonymous"
        binding.reviewUserAvatar.loadImage(review.avatarUrl ?: "")
        binding.reviewText.text = review.review ?: ""
        binding.reviewPostTime.text = review.dateUnix?.let { ActivityItemBuilder.getDateTime(it) } ?: ""
        binding.reviewTag.text = review.score?.let { "[$it]" } ?: ""

        binding.reviewUpVote.visibility = View.GONE
        binding.reviewDownVote.visibility = View.GONE
        binding.reviewTotalVotes.visibility = View.GONE

        binding.root.setOnClickListener {
            val intent = Intent(binding.root.context, MALReviewViewActivity::class.java)
            intent.putExtra("mal_review", review)
            ContextCompat.startActivity(binding.root.context, intent, null)
        }
    }

    override fun getLayout(): Int = R.layout.item_reviews

    override fun initializeViewBinding(view: View): ItemReviewsBinding = ItemReviewsBinding.bind(view)
}
