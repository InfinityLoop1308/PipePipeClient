package org.schabi.newpipe.info_list.dialog

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.schabi.newpipe.databinding.DialogPicturesBinding
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.util.PicassoHelper
import org.schabi.newpipe.util.external_communication.ShareUtils

class PictureDialog : DialogFragment() {

    private var _binding: DialogPicturesBinding? = null
    private val binding get() = _binding!!


    private lateinit var pictureUrls: List<String>

    private val _index: MutableLiveData<Int> = MutableLiveData(0);
    private val index get() = _index as LiveData<Int>


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pictureUrls = arguments?.getStringArrayList(PICTURES).orEmpty()
        require(pictureUrls.isNotEmpty()) { "No Images to display?!" }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogPicturesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        binding.closeButton.setOnClickListener {
            dismiss()
        }

        binding.nextButton.setOnClickListener {
            val newValue = _index.value!! + 1
            if (newValue < pictureUrls.size) {
                _index.postValue(newValue)
            }
        }
        binding.previousButton.setOnClickListener {
            val newValue = _index.value!! - 1
            if (newValue > -1) {
                _index.postValue(newValue)
            }
        }

        index.observe(this.requireActivity()) { current ->
            @SuppressLint("SetTextI18n")
            binding.text.text = "${current + 1}/${pictureUrls.size}"

            val url = pictureUrls[current].replace("http://", "https://")
            PicassoHelper.loadThumbnail(url).into(binding.imageView)

            binding.shareButton.setOnClickListener {
                ShareUtils.shareText(it.context, url, url)
            }
            binding.shareButton.setOnLongClickListener {
                ShareUtils.copyToClipboard(it.context, url)
                true
            }

        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null;
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    companion object {
        private const val PICTURES = "PICTURES"

        @JvmStatic
        fun from(pictures: Collection<Image>): PictureDialog {
            return PictureDialog().apply {
                arguments = Bundle().apply {
                    putStringArrayList(PICTURES, ArrayList(pictures.map { it.url }))
                }
            }
        }
    }
}
