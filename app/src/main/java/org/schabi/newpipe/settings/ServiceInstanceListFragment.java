package org.schabi.newpipe.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.DialogEditTextBinding;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceInstance;
import org.schabi.newpipe.extractor.ServiceInstances;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.util.Constants;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Lists, adds, removes, reorders and selects the instances of a service that implements
 * {@link ServiceInstances}. Which service is shown is passed as {@link Constants#KEY_SERVICE_ID}.
 *
 * <p>The {@code peertube_instance_*} string resources used here are named after their first
 * user, but their texts are generic, so any service can use this screen.</p>
 */
public class ServiceInstanceListFragment extends Fragment {
    private final List<ServiceInstance> instanceList = new ArrayList<>();
    private ServiceInstances provider;
    private ServiceInstance selectedInstance;
    private String serviceName;
    private int serviceId = Constants.NO_SERVICE_ID;

    private InstanceListAdapter instanceListAdapter;

    private ProgressBar progressBar;
    private SharedPreferences sharedPreferences;

    private final CompositeDisposable disposables = new CompositeDisposable();

    /**
     * @param serviceId id of a service that implements {@link ServiceInstances}
     */
    @NonNull
    public static ServiceInstanceListFragment newInstance(final int serviceId) {
        final ServiceInstanceListFragment fragment = new ServiceInstanceListFragment();
        final Bundle args = new Bundle();
        args.putInt(Constants.KEY_SERVICE_ID, serviceId);
        fragment.setArguments(args);
        return fragment;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Lifecycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        serviceId = requireArguments().getInt(Constants.KEY_SERVICE_ID, Constants.NO_SERVICE_ID);
        provider = getProvider();
        if (provider == null) {
            return;
        }
        serviceName = getService().getServiceInfo().getName();
        selectedInstance = provider.getCurrentInstance();
        updateInstanceList();

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        if (provider == null) {
            Toast.makeText(requireContext(), R.string.general_error, Toast.LENGTH_SHORT).show();
            return new View(requireContext());
        }
        return inflater.inflate(R.layout.fragment_instance_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View rootView,
                              @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(rootView, savedInstanceState);
        if (provider == null) {
            return;
        }
        initViews(rootView);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (provider == null) {
            return;
        }
        ThemeHelper.setTitleToAppCompatActivity(getActivity(),
                getString(R.string.service_instances_title, serviceName));
    }

    @Override
    public void onPause() {
        super.onPause();
        saveChanges();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disposables.clear();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_chooser_fragment, menu);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (provider == null) {
            return super.onOptionsItemSelected(item);
        }
        if (item.getItemId() == R.id.menu_item_restore_default) {
            restoreDefaults();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    @Nullable
    private ServiceInstances getProvider() {
        try {
            final StreamingService service = NewPipe.getService(serviceId);
            return service instanceof ServiceInstances ? (ServiceInstances) service : null;
        } catch (final ExtractionException e) {
            return null;
        }
    }

    @NonNull
    private StreamingService getService() {
        try {
            return NewPipe.getService(serviceId);
        } catch (final ExtractionException e) {
            throw new IllegalStateException("No service with id " + serviceId, e);
        }
    }

    private void initViews(@NonNull final View rootView) {
        final TextView instanceHelpTV = rootView.findViewById(R.id.instanceHelpTV);
        final String helpUrl = provider.getInstancesHelpUrl();
        if (helpUrl == null) {
            instanceHelpTV.setVisibility(View.GONE);
        } else {
            instanceHelpTV.setText(getString(R.string.peertube_instance_url_help, helpUrl));
        }

        initButton(rootView);

        final RecyclerView listInstances = rootView.findViewById(R.id.instances);
        listInstances.setLayoutManager(new LinearLayoutManager(requireContext()));

        final ItemTouchHelper itemTouchHelper = new ItemTouchHelper(getItemTouchCallback());
        itemTouchHelper.attachToRecyclerView(listInstances);

        instanceListAdapter = new InstanceListAdapter(requireContext(), itemTouchHelper);
        listInstances.setAdapter(instanceListAdapter);

        progressBar = rootView.findViewById(R.id.loading_progress_bar);
    }

    private void updateInstanceList() {
        instanceList.clear();
        instanceList.addAll(provider.getInstances());
    }

    private void selectInstance(final ServiceInstance instance) {
        provider.selectInstance(instance);
        selectedInstance = provider.getCurrentInstance();
        sharedPreferences.edit().putBoolean(Constants.KEY_MAIN_PAGE_CHANGE, true).apply();
    }

    private void saveChanges() {
        if (provider == null) {
            return;
        }
        provider.setInstances(instanceList);
    }

    private void restoreDefaults() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.restore_defaults)
                .setMessage(R.string.restore_defaults_confirmation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    provider.setInstances(Collections.emptyList());
                    selectedInstance = provider.getCurrentInstance();
                    updateInstanceList();
                    instanceListAdapter.notifyDataSetChanged();
                    sharedPreferences.edit()
                            .putBoolean(Constants.KEY_MAIN_PAGE_CHANGE, true).apply();
                })
                .show();
    }

    private void initButton(final View rootView) {
        final FloatingActionButton fab = rootView.findViewById(R.id.addInstanceButton);
        fab.setOnClickListener(v -> showAddItemDialog(requireContext()));
    }

    private void showAddItemDialog(final Context context) {
        final DialogEditTextBinding dialogBinding
                = DialogEditTextBinding.inflate(getLayoutInflater());
        dialogBinding.dialogEditText.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        dialogBinding.dialogEditText.setHint(R.string.peertube_instance_add_help);

        new AlertDialog.Builder(context)
                .setTitle(R.string.peertube_instance_add_title)
                .setIcon(ServiceHelper.getIconDrawable(context, serviceId))
                .setView(dialogBinding.getRoot())
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    final String url = dialogBinding.dialogEditText.getText().toString();
                    addInstance(url);
                })
                .show();
    }

    private void addInstance(final String url) {
        final String trimmedUrl = url.trim();
        if (trimmedUrl.isEmpty()) {
            return;
        }
        progressBar.setVisibility(View.VISIBLE);
        // the service knows which url forms it accepts and normalizes them
        disposables.add(Single.fromCallable(() -> provider.addInstance(trimmedUrl))
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe((instance) -> {
                    progressBar.setVisibility(View.GONE);
                    add(instance);
                }, e -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(getActivity(), R.string.peertube_instance_add_fail,
                            Toast.LENGTH_SHORT).show();
                }));
    }

    private void add(final ServiceInstance instance) {
        for (final ServiceInstance saved : instanceList) {
            if (saved.getUrl().equals(instance.getUrl())) {
                Toast.makeText(getActivity(), R.string.peertube_instance_add_exists,
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }
        instanceList.add(instance);
        instanceListAdapter.notifyItemInserted(instanceList.size() - 1);
    }

    private ItemTouchHelper.SimpleCallback getItemTouchCallback() {
        return new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN,
                ItemTouchHelper.START | ItemTouchHelper.END) {
            @Override
            public int interpolateOutOfBoundsScroll(@NonNull final RecyclerView recyclerView,
                                                    final int viewSize,
                                                    final int viewSizeOutOfBounds,
                                                    final int totalSize,
                                                    final long msSinceStartScroll) {
                final int standardSpeed = super.interpolateOutOfBoundsScroll(recyclerView,
                        viewSize, viewSizeOutOfBounds, totalSize, msSinceStartScroll);
                final int minimumAbsVelocity = Math.max(12, Math.abs(standardSpeed));
                return minimumAbsVelocity * (int) Math.signum(viewSizeOutOfBounds);
            }

            @Override
            public boolean onMove(@NonNull final RecyclerView recyclerView,
                                  @NonNull final RecyclerView.ViewHolder source,
                                  @NonNull final RecyclerView.ViewHolder target) {
                if (source.getItemViewType() != target.getItemViewType()
                        || instanceListAdapter == null) {
                    return false;
                }

                final int sourceIndex = source.getBindingAdapterPosition();
                final int targetIndex = target.getBindingAdapterPosition();
                instanceListAdapter.swapItems(sourceIndex, targetIndex);
                return true;
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return true;
            }

            @Override
            public void onSwiped(@NonNull final RecyclerView.ViewHolder viewHolder,
                                 final int swipeDir) {
                final int position = viewHolder.getBindingAdapterPosition();
                // do not allow swiping the selected instance
                if (instanceList.get(position).getUrl().equals(selectedInstance.getUrl())) {
                    instanceListAdapter.notifyItemChanged(position);
                    return;
                }
                instanceList.remove(position);
                instanceListAdapter.notifyItemRemoved(position);

                if (instanceList.isEmpty()) {
                    instanceList.add(selectedInstance);
                    instanceListAdapter.notifyItemInserted(0);
                }
            }
        };
    }

    /*//////////////////////////////////////////////////////////////////////////
    // List Handling
    //////////////////////////////////////////////////////////////////////////*/

    private class InstanceListAdapter
            extends RecyclerView.Adapter<InstanceListAdapter.InstanceViewHolder> {
        private final LayoutInflater inflater;
        private final ItemTouchHelper itemTouchHelper;
        private RadioButton lastChecked;

        InstanceListAdapter(final Context context, final ItemTouchHelper itemTouchHelper) {
            this.itemTouchHelper = itemTouchHelper;
            this.inflater = LayoutInflater.from(context);
        }

        public void swapItems(final int fromPosition, final int toPosition) {
            Collections.swap(instanceList, fromPosition, toPosition);
            notifyItemMoved(fromPosition, toPosition);
        }

        @NonNull
        @Override
        public InstanceViewHolder onCreateViewHolder(@NonNull final ViewGroup parent,
                                                     final int viewType) {
            final View view = inflater.inflate(R.layout.item_instance, parent, false);
            return new InstanceViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull final InstanceViewHolder holder, final int position) {
            holder.bind(position, holder);
        }

        @Override
        public int getItemCount() {
            return instanceList.size();
        }

        class InstanceViewHolder extends RecyclerView.ViewHolder {
            private final AppCompatImageView instanceIconView;
            private final TextView instanceNameView;
            private final TextView instanceUrlView;
            private final RadioButton instanceRB;
            private final ImageView handle;

            InstanceViewHolder(final View itemView) {
                super(itemView);

                instanceIconView = itemView.findViewById(R.id.instanceIcon);
                instanceNameView = itemView.findViewById(R.id.instanceName);
                instanceUrlView = itemView.findViewById(R.id.instanceUrl);
                instanceRB = itemView.findViewById(R.id.selectInstanceRB);
                handle = itemView.findViewById(R.id.handle);
            }

            @SuppressLint("ClickableViewAccessibility")
            void bind(final int position, final InstanceViewHolder holder) {
                handle.setOnTouchListener(getOnTouchListener(holder));

                final ServiceInstance instance = instanceList.get(position);
                instanceNameView.setText(instance.getName());
                instanceUrlView.setText(instance.getUrl());
                instanceIconView.setImageDrawable(
                        ServiceHelper.getIconDrawable(itemView.getContext(), serviceId));
                instanceRB.setOnCheckedChangeListener(null);
                if (selectedInstance.getUrl().equals(instance.getUrl())) {
                    if (lastChecked != null && lastChecked != instanceRB) {
                        lastChecked.setChecked(false);
                    }
                    instanceRB.setChecked(true);
                    lastChecked = instanceRB;
                } else {
                    instanceRB.setChecked(false);
                }
                instanceRB.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        selectInstance(instance);
                        if (lastChecked != null && lastChecked != instanceRB) {
                            lastChecked.setChecked(false);
                        }
                        lastChecked = instanceRB;
                    }
                });
            }

            @SuppressLint("ClickableViewAccessibility")
            private View.OnTouchListener getOnTouchListener(final RecyclerView.ViewHolder item) {
                return (view, motionEvent) -> {
                    if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        if (itemTouchHelper != null && getItemCount() > 1) {
                            itemTouchHelper.startDrag(item);
                            return true;
                        }
                    }
                    return false;
                };
            }
        }
    }
}
