package com.isaiahvonrundstedt.fokus.features.task

import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.isaiahvonrundstedt.fokus.R
import com.isaiahvonrundstedt.fokus.components.custom.ItemDecoration
import com.isaiahvonrundstedt.fokus.components.custom.ItemSwipeCallback
import com.isaiahvonrundstedt.fokus.components.enums.SortDirection
import com.isaiahvonrundstedt.fokus.components.extensions.android.createSnackbar
import com.isaiahvonrundstedt.fokus.components.utils.PreferenceManager
import com.isaiahvonrundstedt.fokus.databinding.FragmentTaskBinding
import com.isaiahvonrundstedt.fokus.features.attachments.Attachment
import com.isaiahvonrundstedt.fokus.features.shared.abstracts.BaseAdapter
import com.isaiahvonrundstedt.fokus.features.shared.abstracts.BaseFragment
import com.isaiahvonrundstedt.fokus.features.subject.Subject
import com.isaiahvonrundstedt.fokus.features.task.editor.TaskEditor
import dagger.hilt.android.AndroidEntryPoint
import me.saket.cascade.overrideOverflowMenu
import nl.dionsegijn.konfetti.models.Shape
import nl.dionsegijn.konfetti.models.Size
import java.io.File

@AndroidEntryPoint
class TaskFragment : BaseFragment(), BaseAdapter.ActionListener, TaskAdapter.TaskStatusListener,
    BaseAdapter.ArchiveListener {
    private var _binding: FragmentTaskBinding? = null
    private var controller: NavController? = null

    private val binding get() = _binding!!
    private val taskAdapter = TaskAdapter(this, this, this)
    private val viewModel: TaskViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = FragmentTaskBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.actionButton.transitionName = TRANSITION_ELEMENT_ROOT

        getParentToolbar()?.run {
            setTitle(getToolbarTitle())
            menu?.clear()
            inflateMenu(R.menu.menu_tasks)
            overrideOverflowMenu(::customPopupProvider)
            setOnMenuItemClickListener(::onMenuItemClicked)
        }

        with(binding.recyclerView) {
            addItemDecoration(ItemDecoration(context))
            layoutManager = LinearLayoutManager(context)
            adapter = taskAdapter

            ItemTouchHelper(ItemSwipeCallback(context, taskAdapter))
                .attachToRecyclerView(this)
        }

        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        ItemTouchHelper(ItemSwipeCallback(requireContext(), taskAdapter))
            .attachToRecyclerView(binding.recyclerView)
    }

    override fun onStart() {
        super.onStart()
        /**
         * Get the NavController here so
         * that it doesn't crash when
         * the host activity is recreated.
         */
        controller = Navigation.findNavController(requireActivity(), R.id.navigationHostFragment)

        viewModel.tasks.observe(viewLifecycleOwner) {
            taskAdapter.submitList(it)
        }
        viewModel.isEmpty.observe(viewLifecycleOwner) {
            when (viewModel.filterOption) {
                TaskViewModel.Constraint.ALL -> {
                    binding.emptyViewPendingTasks.isVisible = it
                    binding.emptyViewFinishedTasks.isVisible = false
                }
                TaskViewModel.Constraint.PENDING -> {
                    binding.emptyViewPendingTasks.isVisible = it
                    binding.emptyViewFinishedTasks.isVisible = false
                }
                TaskViewModel.Constraint.FINISHED -> {
                    binding.emptyViewPendingTasks.isVisible = false
                    binding.emptyViewFinishedTasks.isVisible = it
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        binding.actionButton.setOnClickListener {
            controller?.navigate(R.id.action_to_navigation_editor_task, null, null,
                FragmentNavigatorExtras(it to TRANSITION_ELEMENT_ROOT))
        }
    }

    // Update the task in the database then show
    // snackbar feedback and also if the sounds if turned on
    // play a fokus sound.
    override fun onStatusChanged(taskPackage: TaskPackage, isFinished: Boolean) {
        viewModel.update(taskPackage.task)
        if (isFinished) {
            createSnackbar(R.string.feedback_task_marked_as_finished, binding.recyclerView)

            with(PreferenceManager(context)) {
                if (confetti) {
                    binding.confettiView.build()
                        .addColors(Color.YELLOW, Color.MAGENTA, Color.CYAN)
                        .setDirection(0.0, 359.0)
                        .setSpeed(1f, 5f)
                        .setFadeOutEnabled(true)
                        .setTimeToLive(1000L)
                        .addShapes(Shape.Square, Shape.Circle)
                        .addSizes(Size(12, 5f))
                        .setPosition(binding.confettiView.x + binding.confettiView.width / 2,
                            binding.confettiView.y + binding.confettiView.height / 3)
                        .burst(100)
                }

                if (sounds)
                    RingtoneManager.getRingtone(requireContext(),
                        Uri.parse(PreferenceManager.DEFAULT_SOUND)).play()
            }
        }
    }

    // Callback from the RecyclerView Adapter
    override fun <T> onActionPerformed(t: T, action: BaseAdapter.ActionListener.Action,
                                       container: View?) {
        if (t is TaskPackage) {
            when (action) {
                // Create the intent to the editorUI and pass the extras
                // and wait for the result.
                BaseAdapter.ActionListener.Action.SELECT -> {
                    val transitionName = TRANSITION_ELEMENT_ROOT + t.task.taskID

                    val args = bundleOf(
                        TaskEditor.EXTRA_TASK to Task.toBundle(t.task),
                        TaskEditor.EXTRA_ATTACHMENTS to t.attachments,
                        TaskEditor.EXTRA_SUBJECT to t.subject?.let { Subject.toBundle(it) }
                    )

                    container?.also {
                        controller?.navigate(R.id.action_to_navigation_editor_task, args, null,
                            FragmentNavigatorExtras(it to transitionName))
                    }
                }
                // The item has been swiped down from the recyclerView
                // remove the item from the database and show a snackbar
                // feedback
                BaseAdapter.ActionListener.Action.DELETE -> {
                    viewModel.remove(t.task)

                    createSnackbar(R.string.feedback_task_removed, binding.recyclerView).run {
                        addCallback(object: Snackbar.Callback() {
                            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                                super.onDismissed(transientBottomBar, event)

                                if (event != DISMISS_EVENT_ACTION)
                                    t.attachments.forEach { attachment ->
                                        if (attachment.type == Attachment.TYPE_IMPORTED_FILE)
                                            attachment.target?.also { File(it).delete() }
                                    }
                            }
                        })
                        setAction(R.string.button_undo) {
                            viewModel.insert(t.task, t.attachments)
                        }
                    }
                }
            }
        }
    }

    override fun <T> onItemArchive(t: T) {
        if (t is TaskPackage) {
            t.task.isTaskArchived = true
            viewModel.update(t.task)
        }
    }

    private fun onMenuItemClicked(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.action_name_sort_ascending -> {
                viewModel.sort = TaskViewModel.Sort.NAME
                viewModel.sortDirection = SortDirection.ASCENDING
            }
            R.id.action_name_sort_descending -> {
                viewModel.sort = TaskViewModel.Sort.NAME
                viewModel.sortDirection = SortDirection.DESCENDING
            }
            R.id.action_due_sort_ascending -> {
                viewModel.sort = TaskViewModel.Sort.DUE
                viewModel.sortDirection = SortDirection.ASCENDING
            }
            R.id.action_due_sort_descending -> {
                viewModel.sort = TaskViewModel.Sort.DUE
                viewModel.sortDirection = SortDirection.DESCENDING
            }
            R.id.action_filter_all -> {
                viewModel.filterOption = TaskViewModel.Constraint.ALL
                getParentToolbar()?.setTitle(getToolbarTitle())
            }
            R.id.action_filter_pending -> {
                viewModel.filterOption = TaskViewModel.Constraint.PENDING
                getParentToolbar()?.setTitle(getToolbarTitle())
            }
            R.id.action_filter_finished -> {
                viewModel.filterOption = TaskViewModel.Constraint.FINISHED
                getParentToolbar()?.setTitle(getToolbarTitle())
            }
            R.id.action_archived -> {
                controller?.navigate(R.id.action_to_navigation_archived_task)
            }
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    @StringRes
    private fun getToolbarTitle(): Int {
        return when (viewModel.filterOption) {
            TaskViewModel.Constraint.ALL -> R.string.activity_tasks
            TaskViewModel.Constraint.PENDING -> R.string.activity_tasks_pending
            TaskViewModel.Constraint.FINISHED -> R.string.activity_tasks_finished
        }
    }
}
