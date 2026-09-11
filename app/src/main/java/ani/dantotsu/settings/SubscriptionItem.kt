package ani.dantotsu.settings

import android.content.Intent
import android.view.View
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemSubscriptionBinding
import ani.dantotsu.loadImage
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.notifications.subscription.SubscriptionHelper
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.viewbinding.BindableItem

class SubscriptionItem(
    val id: Int,
    private val media: SubscriptionHelper.Companion.SubscribeMedia,
    private val adapter: GroupieAdapter,
    private val onItemRemoved: (Int) -> Unit
) : BindableItem<ItemSubscriptionBinding>() {
    private lateinit var binding: ItemSubscriptionBinding

    override fun bind(viewBinding: ItemSubscriptionBinding, position: Int) {
        binding = viewBinding
        val context = binding.root.context

        binding.subscriptionName.text = media.name
        binding.root.setOnClickListener {
            val activity = context as? androidx.fragment.app.FragmentActivity
            val options = if (activity != null) {
                ActivityOptionsCompat.makeSceneTransitionAnimation(
                    activity,
                    binding.subscriptionCover,
                    ViewCompat.getTransitionName(binding.subscriptionCover)!!
                ).toBundle()
            } else null
            ContextCompat.startActivity(
                context,
                Intent(context, MediaDetailsActivity::class.java).putExtra("mediaId", media.id),
                options
            )
        }
        binding.subscriptionCover.loadImage(media.image)
        binding.deleteSubscription.setOnClickListener {
            SubscriptionHelper.deleteSubscription(id, true)
            adapter.remove(this)
            onItemRemoved(id)
        }
    }

    override fun getLayout(): Int = R.layout.item_subscription

    override fun initializeViewBinding(view: View): ItemSubscriptionBinding =
        ItemSubscriptionBinding.bind(view)
}