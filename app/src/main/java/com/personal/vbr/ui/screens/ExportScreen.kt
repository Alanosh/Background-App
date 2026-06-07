package com.personal.vbr.ui.screens

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.personal.vbr.databinding.FragmentExportBinding
import com.personal.vbr.media.VideoEncoder
import com.personal.vbr.ui.viewmodel.EditorViewModel
import com.personal.vbr.util.MediaUtils
import kotlinx.coroutines.launch

/**
 * Export screen: resolution picker, estimated file size, progress bar.
 */
class ExportFragment : Fragment() {

    private var _binding: FragmentExportBinding? = null
    private val binding get() = _binding!!

    private val viewModel: EditorViewModel by activityViewModels()

    private var selectedResolution = VideoEncoder.ExportResolution.P720
    private var sourceUri: Uri? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupResolutionPicker()
        setupExportButton()
        observeExportState()
    }

    private fun setupResolutionPicker() {
        // Resolution radio group
        binding.radioGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedResolution = when (checkedId) {
                binding.radio480p.id  -> VideoEncoder.ExportResolution.P480
                binding.radio720p.id  -> VideoEncoder.ExportResolution.P720
                binding.radio1080p.id -> VideoEncoder.ExportResolution.P1080
                else -> VideoEncoder.ExportResolution.P720
            }
            updateSizeEstimate()
        }

        // Default to 720p
        binding.radio720p.isChecked = true
        updateSizeEstimate()
    }

    private fun updateSizeEstimate() {
        val durationMs = viewModel.durationMs.value
        val sizeMb = MediaUtils.estimateExportSizeMb(durationMs, selectedResolution.bitrateBps)
        binding.tvSizeEstimate.text = "Estimated size: ~${"%.1f".format(sizeMb)}MB"
    }

    private fun setupExportButton() {
        binding.btnStartExport.setOnClickListener {
            val uri = sourceUri ?: run {
                Toast.makeText(context, "No video loaded", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.startExport(
                resolution  = selectedResolution,
                sourceUri   = uri
            )
            binding.exportProgressGroup.visibility = View.VISIBLE
            binding.btnStartExport.isEnabled = false
            binding.btnCancelExport.visibility = View.VISIBLE
        }

        binding.btnCancelExport.setOnClickListener {
            viewModel.cancelExport()
            binding.exportProgressGroup.visibility = View.GONE
            binding.btnStartExport.isEnabled = true
            binding.btnCancelExport.visibility = View.GONE
        }
    }

    private fun observeExportState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.exportProgress.collect { progress ->
                        if (progress != null) {
                            binding.exportProgressBar.progress = (progress * 100).toInt()
                            binding.tvExportProgress.text = "${(progress * 100).toInt()}%"
                        }
                    }
                }

                launch {
                    viewModel.exportComplete.collect { uri ->
                        if (uri != null) {
                            binding.exportProgressGroup.visibility = View.GONE
                            binding.btnStartExport.isEnabled = true
                            binding.btnCancelExport.visibility = View.GONE
                            Toast.makeText(context, "Saved to gallery!", Toast.LENGTH_LONG).show()
                            viewModel.clearExportResult()
                            findNavController().popBackStack()
                        }
                    }
                }

                launch {
                    viewModel.exportError.collect { error ->
                        if (error != null) {
                            binding.exportProgressGroup.visibility = View.GONE
                            binding.btnStartExport.isEnabled = true
                            Toast.makeText(context, "Export failed: $error", Toast.LENGTH_LONG).show()
                            viewModel.clearExportResult()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
