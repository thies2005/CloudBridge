package ca.pkay.rcloneexplorer.RecyclerViewAdapters;


import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;


import java.util.List;

import ca.pkay.rcloneexplorer.Items.FilterEntry;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.util.ExposedChoice;

public class FilterEntryRecyclerViewAdapter extends RecyclerView.Adapter<FilterEntryRecyclerViewAdapter.ViewHolder> {
    private List<FilterEntry> filterEntries;
    private View view;
    private final Context context;


    public FilterEntryRecyclerViewAdapter(List<FilterEntry> filterEntries, Context context) {
        this.filterEntries = filterEntries;
        this.context = context;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        view = LayoutInflater.from(parent.getContext()).inflate(R.layout.fragment_filter_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, final int position) {
        final FilterEntry selectedFilterEntry = filterEntries.get(position);

        holder.filterText.setText(selectedFilterEntry.filter);
        holder.filterText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                selectedFilterEntry.filter = s.toString();
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });




        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(holder.itemView.getContext(),
                android.R.layout.simple_list_item_1, new String[]{ context.getString(R.string.filter_entry_filtertype_include), context.getString(R.string.filter_entry_filtertype_exclude) });
        holder.filterTypeSpinner.setChoices(spinnerAdapter);
        // Restore the stored type without firing the write-back listener, then attach it.
        holder.filterTypeSpinner.setOnChoiceChanged(null);
        holder.filterTypeSpinner.selectPosition(selectedFilterEntry.filterType);
        holder.filterTypeSpinner.setOnChoiceChanged(pos -> {
            selectedFilterEntry.filterType = pos;
            return null;
        });

        holder.fileOptions.setOnClickListener(v -> removeItem(selectedFilterEntry));

    }

    public void removeItem(FilterEntry filterEntry) {
        int index = filterEntries.indexOf(filterEntry);
        if (index >= 0) {
            filterEntries.remove(index);
            notifyItemRemoved(index);
        }
    }

    @Override
    public int getItemCount() {
        return filterEntries.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        final View view;
        final ExposedChoice filterTypeSpinner;
        final EditText filterText;
        final ImageButton fileOptions;

        ViewHolder(View itemView) {
            super(itemView);
            this.view = itemView;
            this.filterTypeSpinner = view.findViewById(R.id.filter_entry_filter_type);
            this.filterText = view.findViewById(R.id.filter_entry_filter_text);
            this.fileOptions = view.findViewById(R.id.filter_entry_delete);
        }
    }
}
