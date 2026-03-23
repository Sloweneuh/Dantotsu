package ani.dantotsu.media

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.net.toUri
import ani.dantotsu.R
import ani.dantotsu.copyToClipboard
import ani.dantotsu.loadImage
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.others.CustomBottomDialog

class QuicklinksBottomSheetFragment : CustomBottomDialog() {
    companion object {
        fun newInstance(siteName: String, entries: ArrayList<ani.dantotsu.connections.malsync.QuicklinkEntry>): QuicklinksBottomSheetFragment {
            val f = QuicklinksBottomSheetFragment()
            val args = Bundle()
            args.putString("siteName", siteName)
            args.putSerializable("entries", entries)
            f.arguments = args
            return f
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val siteName = arguments?.getString("siteName") ?: ""
        val entries = arguments?.getSerializable("entries") as? ArrayList<ani.dantotsu.connections.malsync.QuicklinkEntry> ?: arrayListOf()

        setTitleText(siteName)

        val inflater = LayoutInflater.from(requireContext())

        entries.forEach { entry ->
            try {
                val itemView = inflater.inflate(R.layout.item_quicklink_entry, null, false)
                val tv = itemView.findViewById<TextView>(R.id.quicklinkItemTitle)

                val titleText = entry.title ?: entry.identifier ?: entry.page ?: entry.url ?: siteName
                tv.text = titleText

                val url = entry.url ?: entry.page
                if (!url.isNullOrBlank()) {
                    itemView.setSafeOnClickListener {
                        try { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } catch (_: Throwable) {}
                    }
                    itemView.setOnLongClickListener { copyToClipboard(url); true }
                }

                addView(itemView)
            } catch (_: Throwable) {}
        }
    }
}
