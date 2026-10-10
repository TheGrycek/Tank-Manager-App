package com.example.tank_manager.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tank_manager.R;
import com.example.tank_manager.model.RecyclerModel;

import java.util.List;

public class RecyclerAdapter extends RecyclerView.Adapter<RecyclerAdapter.ViewHolder> {
    Context context;
    List<RecyclerModel> recyclerModels;
    private OnItemClickListener listener;

    public RecyclerAdapter(Context context, List<RecyclerModel> recyclerModels) {
        this.context = context;
        this.recyclerModels = recyclerModels;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.bt_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.itemName.setText(recyclerModels.get(position).getName());
        holder.itemUUID.setText(recyclerModels.get(position).getUuid());
    }

    @Override
    public int getItemCount() {
        return recyclerModels.size();
    }

    public interface OnItemClickListener {
        void onItemClick(String uuid);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        private TextView itemName, itemUUID;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            itemName = itemView.findViewById(R.id.item_bt_name);
            itemUUID = itemView.findViewById(R.id.item_bt_uuid);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (listener != null && position != RecyclerView.NO_POSITION) {
                    listener.onItemClick(recyclerModels.get(position).getUuid());
                }
            });
        }
    }

}
