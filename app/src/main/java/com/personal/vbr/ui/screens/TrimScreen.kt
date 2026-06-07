package com.personal.vbr.ui.screens

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.personal.vbr.databinding.FragmentTrimBinding
import com.personal.vbr.media.VideoSplitter
import com.personal.vbr.ui.viewmodel.EditorViewModel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Trim/split screen.
 *
 * - RangeSeekBar defines trim start/end.
 * - Split button adds a cut point at the current playhead position.
 * - All operations use VideoSplitter (remux, no re-encode).
 */
class TrimFragment : Fragment() {

    private var _binding: FragmentTrimBinding? = null
    private val binding get() = _binding!!

    private val viewModel: EditorViewModel by activityViewModels()
    private val splitter by lazy { VideoSplitter(requireContext()) }

    private var trimStartMs = 0L
    private var trimEndMs   = -1L
    private val splitPoints = mutableListOf<Long>()
    private var sourceUri: Uri? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTrimBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val duration = viewModel.durationMs.value
        binding.rangeSeekBar.setRange(0L, duration)
        binding.rangeSeekBar.setOnRangeChangedListener { start, end ->
            trimStartMs = start
            trimEndMs   = end
            binding.tvTrimRange.text =
                "Trim: ${formatMs(start)} → ${formatMs(end)}"
        }

        binding.btnAddSplit.setOnClickListener {
            val current = viewModel.currentMs.value
            splitPoints.add(current)
            splitPoints.sort()
            binding.tvSplitPoints.text =
                "Split at: ${splitPoints.joinToString { formatMs(it) }}"
        }

        binding.btnClearSplits.setOnClickListener {
            splitPoints.clear()
            binding.tvSplitPoints.text = "No split points"
        }

        binding.btnApplyTrim.setOnClickListener { applyTrim() }
        binding.btnApplySplit.setOnClickListener { applySplit() }
    }

    private fun applyTrim() {
        val uri = sourceUri ?: return
        val outputDir = requireContext().getExternalFilesDir(null) ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            binding.btnApplyTrim.isEnabled = false
            val result = splitter.trim(uri, trimStartMs, trimEndMs, outputDir)
            binding.btnApplyTrim.isEnabled = true

            if (result != null) {
                Toast.makeText(context, "Trimmed: ${result.name}", Toast.LENGTH_LONG).show()
                findNavController().popBackStack()
            } else {
                Toast.makeText(context, "Trim failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applySplit() {
        val uri = sourceUri ?: return
        if (splitPoints.isEmpty()) {
            Toast.makeText(context, "Add split points first", Toast.LENGTH_SHORT).show()
            return
        }
        val outputDir = requireContext().getExternalFilesDir(null) ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            binding.btnApplySplit.isEnabled = false
            val results = splitter.split(uri, splitPoints, outputDir)
            binding.btnApplySplit.isEnabled = true

            Toast.makeText(context,
                "${results.size} segments created", Toast.LENGTH_LONG).show()
            findNavController().popBackStack()
        }
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
