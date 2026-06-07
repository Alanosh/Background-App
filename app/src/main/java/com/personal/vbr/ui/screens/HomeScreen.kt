package com.personal.vbr.ui.screens

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.personal.vbr.R
import com.personal.vbr.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { navigateToEditor(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnImportVideo.setOnClickListener {
            videoPicker.launch("video/*")
        }
    }

    private fun navigateToEditor(uri: Uri) {
        val args = Bundle().apply { putParcelable("video_uri", uri) }
        findNavController().navigate(R.id.action_home_to_editor, args)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
