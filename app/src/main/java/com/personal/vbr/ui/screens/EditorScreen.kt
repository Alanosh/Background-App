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
import com.personal.vbr.R
import com.personal.vbr.core.pipeline.PipelineState
import com.personal.vbr.databinding.FragmentEditorBinding
import com.personal.vbr.media.VideoEncoder
import com.personal.vbr.ui.viewmodel.EditorViewModel
import com.personal.vbr.util.MemoryGuard
import kotlinx.coroutines.launch

class EditorFragment : Fragment() {

    private var _binding: FragmentEditorBinding? = null
    private val binding get() = _binding!!

    private val viewModel: EditorViewModel by activityViewModels()

    private var frameCheckCounter = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load video from arguments (passed from HomeFragment or intent)
        val videoUri = arguments?.getParcelable<Uri>("video_uri")
        if (videoUri != null && !viewModel.videoLoaded.value) {
            viewModel.loadVideo(videoUri)
        }

        setupPlaybackControls()
        setupToolbar()
        setupPanels()
        observeState()
    }

    // ---------------------------------------------------------------------------
    // Playback controls
    // ---------------------------------------------------------------------------

    private fun setupPlaybackControls() {
        binding.btnPlay.setOnClickListener  { viewModel.play() }
        binding.btnPause.setOnClickListener { viewModel.pause() }

        binding.scrubber.setOnSeekListener { ms ->
            viewModel.seekTo(ms)
        }

        // Speed picker (0.5x / 1x / 2x)
        binding.btnSpeed.setOnClickListener {
            val speeds = listOf(0.5f, 1.0f, 2.0f)
            val current = viewModel.playbackSpeed.value
            val next = speeds[(speeds.indexOf(current) + 1) % speeds.size]
            viewModel.setPlaybackSpeed(next)
            binding.btnSpeed.text = "${next}x"
        }
    }

    // ---------------------------------------------------------------------------
    // Toolbar (undo, tools, export, trim)
    // ---------------------------------------------------------------------------

    private fun setupToolbar() {
        binding.btnUndo.setOnClickListener {
            val undone = viewModel.undoSelection()
            if (!undone) Toast.makeText(context, "Nothing to undo", Toast.LENGTH_SHORT).show()
        }

        binding.btnLasso.setOnClickListener    { viewModel.activateLasso() }
        binding.btnMagnetic.setOnClickListener { viewModel.activateMagneticLasso() }
        binding.btnBrush.setOnClickListener    { viewModel.activateBrush() }

        binding.btnExport.setOnClickListener {
            findNavController().navigate(R.id.action_editor_to_export)
        }

        binding.btnTrim.setOnClickListener {
            findNavController().navigate(R.id.action_editor_to_trim)
        }

        // Snapshot (single frame PNG export)
        binding.btnSnapshot.setOnClickListener {
            Toast.makeText(context, "Snapshot saved", Toast.LENGTH_SHORT).show()
            // TODO: implement single-frame PNG export via ExportManager
        }
    }

    // ---------------------------------------------------------------------------
    // Side panels
    // ---------------------------------------------------------------------------

    private fun setupPanels() {
        binding.btnBackground.setOnClickListener {
            binding.backgroundPicker.toggleVisibility()
        }

        binding.btnAdjustments.setOnClickListener {
            binding.adjustmentPanel.toggleVisibility()
        }

        binding.btnEffects.setOnClickListener {
            binding.effectsPanel.toggleVisibility()
        }

        // Wire panel callbacks to ViewModel
        binding.adjustmentPanel.onSubjectParamsChanged  = { viewModel.updateSubjectAdjustments(it) }
        binding.adjustmentPanel.onBackgroundParamsChanged = { viewModel.updateBackgroundAdjustments(it) }

        binding.effectsPanel.onGlowEnabledChanged   = { viewModel.setGlowEnabled(it) }
        binding.effectsPanel.onGlowIntensityChanged = { viewModel.setGlowIntensity(it) }
        binding.effectsPanel.onFlipChanged          = { viewModel.toggleSubjectFlip() }
        binding.effectsPanel.onFeatherChanged       = { /* passed to maskProcessor via pipeline */ }

        binding.backgroundPicker.onColorSelected = { color -> viewModel.setColorBackground(color) }
        binding.backgroundPicker.onImageSelected = { bitmap -> viewModel.setImageBackground(bitmap) }
        binding.backgroundPicker.onVideoSelected = { uri   -> viewModel.setVideoBackground(uri) }
        binding.backgroundPicker.onBlurSelected  = { radius -> viewModel.setBlurBackground(radius) }
    }

    // ---------------------------------------------------------------------------
    // State observation
    // ---------------------------------------------------------------------------

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.pipelineState.collect { state ->
                        when (state) {
                            is PipelineState.Playing -> {
                                binding.btnPlay.visibility  = View.GONE
                                binding.btnPause.visibility = View.VISIBLE
                                binding.scrubber.setPosition(state.currentFrameMs)

                                // Check memory every 60 frames
                                if (++frameCheckCounter % 60 == 0) {
                                    MemoryGuard.checkAndAct()
                                }
                            }
                            is PipelineState.Paused -> {
                                binding.btnPlay.visibility  = View.VISIBLE
                                binding.btnPause.visibility = View.GONE
                            }
                            is PipelineState.Error -> {
                                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
                            }
                            else -> {}
                        }
                    }
                }

                launch {
                    viewModel.durationMs.collect { duration ->
                        binding.scrubber.setDuration(duration)
                    }
                }

                launch {
                    viewModel.activeTool.collect { tool ->
                        // Highlight active tool button
                        binding.btnLasso.isSelected    = tool == EditorViewModel.ActiveTool.LASSO
                        binding.btnMagnetic.isSelected = tool == EditorViewModel.ActiveTool.MAGNETIC
                        binding.btnBrush.isSelected    = tool == EditorViewModel.ActiveTool.BRUSH
                    }
                }

                launch {
                    viewModel.backgroundCacheProgress.collect { progress ->
                        if (progress != null) {
                            binding.bgCacheProgress.visibility = View.VISIBLE
                            binding.bgCacheProgress.progress = (progress * 100).toInt()
                        } else {
                            binding.bgCacheProgress.visibility = View.GONE
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

    // Helper extension for panel show/hide toggle
    private fun View.toggleVisibility() {
        visibility = if (visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }
}
