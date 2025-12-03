package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.DialogTitleSelectorBinding
import ani.dantotsu.databinding.ItemTitleSelectBinding

class TitleSelectorDialog : BottomSheetDialogFragment() {

    private var _binding: DialogTitleSelectorBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val ARG_TITLES = "titles"
        private const val ARG_SITE_NAME = "site_name"
        private const val ARG_URL_TEMPLATE = "url_template"

        fun newInstance(
            titles: ArrayList<String>,
            siteName: String,
            urlTemplate: String
        ): TitleSelectorDialog {
            val dialog = TitleSelectorDialog()
            val args = Bundle().apply {
                putStringArrayList(ARG_TITLES, titles)
                putString(ARG_SITE_NAME, siteName)
                putString(ARG_URL_TEMPLATE, urlTemplate)
            }
            dialog.arguments = args
            return dialog
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogTitleSelectorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val titles = arguments?.getStringArrayList(ARG_TITLES) ?: arrayListOf()
        val siteName = arguments?.getString(ARG_SITE_NAME) ?: ""
        val urlTemplate = arguments?.getString(ARG_URL_TEMPLATE) ?: ""

        binding.titleSelectorTitle.text = getString(R.string.select_title_for_search, siteName)
        binding.titleSelectorRecycler.layoutManager = LinearLayoutManager(context)
        binding.titleSelectorRecycler.adapter = TitleAdapter(titles) { selectedTitle ->
            // Create URL from template by replacing {TITLE} with encoded title
            val encodedTitle = java.net.URLEncoder.encode(selectedTitle, "UTF-8").replace("+", "%20")
            val url = urlTemplate.replace("{TITLE}", encodedTitle)

            // Open URL
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            dismiss()
        }

        binding.titleSelectorCancel.setOnClickListener {
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class TitleAdapter(
        private val titles: List<String>,
        private val onTitleSelected: (String) -> Unit
    ) : RecyclerView.Adapter<TitleAdapter.TitleViewHolder>() {

        inner class TitleViewHolder(val binding: ItemTitleSelectBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TitleViewHolder {
            val binding = ItemTitleSelectBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return TitleViewHolder(binding)
        }

        override fun onBindViewHolder(holder: TitleViewHolder, position: Int) {
            val title = titles[position]
            holder.binding.titleText.text = title
            holder.binding.root.setOnClickListener {
                onTitleSelected(title)
            }
        }

        override fun getItemCount(): Int = titles.size
    }
}

