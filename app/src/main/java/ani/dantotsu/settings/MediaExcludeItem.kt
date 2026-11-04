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
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.viewbinding.BindableItem

class MediaExcludeItem(
    private val entry: String,
    private val adapter: GroupieAdapter,
    private val onRemoved: (String) -> Unit
) : BindableItem<ItemSubscriptionBinding>() {

    override fun bind(viewBinding: ItemSubscriptionBinding, position: Int) {
        val parts = entry.split("||", limit = 3)
        val id = parts[0]
        val coverUrl = parts.getOrElse(1) { "" }
        val name = parts.getOrElse(2) { id }

        viewBinding.subscriptionName.text = name
        viewBinding.subscriptionCover.loadImage(coverUrl.ifBlank { null })

        viewBinding.root.setOnClickListener {
            val mediaId = id.toIntOrNull() ?: return@setOnClickListener
            val activity = viewBinding.root.context as? androidx.fragment.app.FragmentActivity
            val options = if (activity != null) {
                ActivityOptionsCompat.makeSceneTransitionAnimation(
                    activity,
                    viewBinding.subscriptionCover,
                    ViewCompat.getTransitionName(viewBinding.subscriptionCover)!!
                ).toBundle()
            } else null
            ContextCompat.startActivity(
                viewBinding.root.context,
                Intent(viewBinding.root.context, MediaDetailsActivity::class.java)
                    .putExtra("mediaId", mediaId),
                options
            )
        }

        viewBinding.deleteSubscription.setOnClickListener {
            adapter.remove(this)
            onRemoved(entry)
        }
    }

    override fun getLayout(): Int = R.layout.item_subscription

    override fun initializeViewBinding(view: View): ItemSubscriptionBinding =
        ItemSubscriptionBinding.bind(view)
}
