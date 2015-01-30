package minechem.apparatus.prefab.tileEntity.energy;

import minechem.apparatus.prefab.storage.EnergyStorage;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

public abstract class RFBase extends TileEntity
{
    protected EnergyStorage energy;

    public RFBase()
    {
        energy = new EnergyStorage();
    }

    public RFBase(int capacity)
    {
        energy = new EnergyStorage(capacity);
    }

    public RFBase(int capacity, int maxTransfer)
    {
        energy = new EnergyStorage(capacity, maxTransfer);
    }

    public RFBase(int capacity, int maxInput, int maxOutput)
    {
        energy = new EnergyStorage(capacity, maxInput, maxOutput);
    }

    public boolean consumeEnergy(int amount)
    {
        return this.energy.consumeEnergy(amount);
    }

    public boolean hasEnergy(int amount)
    {
        return this.energy.hasEnergy(amount);
    }

    public boolean generateEnergy(int amount)
    {
        return this.energy.generateEnergy(amount);
    }

    @Override
    public void writeToNBT(NBTTagCompound tagCompound)
    {
        super.writeToNBT(tagCompound);
        energy.writeToNBT(tagCompound);
    }

    @Override
    public void readFromNBT(NBTTagCompound tagCompound)
    {
        super.readFromNBT(tagCompound);
        energy.readFromNBT(tagCompound);
    }
}
