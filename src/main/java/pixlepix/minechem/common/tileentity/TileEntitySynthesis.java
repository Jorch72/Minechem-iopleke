package pixlepix.minechem.common.tileentity;

import buildcraft.api.gates.ActionManager;
import buildcraft.api.gates.ITrigger;
import buildcraft.api.gates.ITriggerProvider;
import buildcraft.api.inventory.ISpecialInventory;
import buildcraft.api.transport.IPipe;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.ForgeDirection;
import net.minecraftforge.fluids.*;
import pixlepix.minechem.api.recipe.SynthesisRecipe;
import pixlepix.minechem.api.util.Util;
import pixlepix.minechem.client.ModelSynthesizer;
import pixlepix.minechem.common.MinechemItems;
import pixlepix.minechem.common.gates.IMinechemTriggerProvider;
import pixlepix.minechem.common.gates.MinechemTriggers;
import pixlepix.minechem.common.inventory.BoundedInventory;
import pixlepix.minechem.common.inventory.Transactor;
import pixlepix.minechem.common.items.ItemElement;
import pixlepix.minechem.common.items.ItemMolecule;
import pixlepix.minechem.common.network.PacketHandler;
import pixlepix.minechem.common.network.PacketSynthesisUpdate;
import pixlepix.minechem.common.recipe.SynthesisRecipeHandler;
import pixlepix.minechem.common.utils.MinechemHelper;
import pixlepix.minechem.computercraft.IMinechemMachinePeripheral;
import pixlepix.minechem.fluid.FluidHelper;
import pixlepix.minechem.fluid.IMinechemFluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

public class TileEntitySynthesis extends MinechemTileEntity implements ISidedInventory, ITriggerProvider, IMinechemTriggerProvider,
        ISpecialInventory, IMinechemMachinePeripheral, IFluidHandler {
    public static final int[] kOutput = {0};
    public static final int[] kRecipe = {1, 2, 3, 4, 5, 6, 7, 8, 9};
    public static final int[] kBottles = {10, 11, 12, 13};
    public static final int[] kStorage = {14, 15, 16, 17, 18, 19, 20, 21, 22};
    public static final int[] kJournal = {23};
    //Slots that contain *real* items
    //For the purpose of dropping upon break. These are bottles, storage, and 
    // journal.
    public static final int[] kRealSlots;

    // Ensure that the list of real slots stays in sync with the above defs.
    static {
        ArrayList l = new ArrayList();
        for (int v : kBottles) {
            l.add(v);
        }
        for (int v : kStorage) {
            l.add(v);
        }
        for (int v : kJournal) {
            l.add(v);
        }
        kRealSlots = new int[l.size()];
        for (int idx = 0; idx < l.size(); idx++) {
            // Jump through some autounboxing hoops due to primitive types not 
            // being first-class types.
            kRealSlots[idx] = (Integer) l.get(idx);
        }
    }

    private SynthesisRecipe currentRecipe;
    public ModelSynthesizer model;
    public static final int kSizeOutput = 1;
    public static final int kSizeRecipe = 9;
    public static final int kSizeBottles = 4;
    public static final int kSizeStorage = 9;
    public static final int kSizeJournal = 1;
    public static final int kStartOutput = 0;
    public static final int kStartRecipe = 1;
    public static final int kStartBottles = 10;
    public static final int kStartStorage = 14;
    public static final int kStartJournal = 23;
    private final BoundedInventory recipeMatrix = new BoundedInventory(this, kRecipe);
    private final BoundedInventory storageInventory = new BoundedInventory(this, kStorage);
    private final BoundedInventory tubeInventory = new BoundedInventory(this, kBottles);
    private final BoundedInventory outputInventory = new BoundedInventory(this, kOutput);
    private final BoundedInventory journalInventory = new BoundedInventory(this, kJournal);
    private final Transactor testTubeTransactor = new Transactor(tubeInventory);
    private final Transactor storageTransactor = new Transactor(storageInventory);
    private final Transactor outputTransactor = new Transactor(outputInventory);
    private final Transactor recipeMatrixTransactor = new Transactor(recipeMatrix);
    private final Transactor journalTransactor = new Transactor(journalInventory, 1);

    private static final int MIN_ENERGY_RECIEVED = 30;
    private static final int MAX_ENERGY_RECIEVED = 200;
    private static final int MIN_ACTIVATION_ENERGY = 100;
    private static final int MAX_ENERGY_STORED = 1022220;

    private boolean hasFullEnergy;

    public TileEntitySynthesis() {
        inventory = new ItemStack[getSizeInventory()];

        if (FMLCommonHandler.instance().getSide() == Side.CLIENT) {
            model = new ModelSynthesizer();
        }
        ActionManager.registerTriggerProvider(this);
    }

    @Override
    public int addItem(ItemStack stack, boolean doAdd, ForgeDirection direction) {
        return storageTransactor.add(stack, doAdd);
    }

    public boolean canTakeOutputStack() {
        return inventory[kOutput[0]] != null && hasEnoughPowerForCurrentRecipe() && takeStacksFromStorage(false);
    }

    public void clearRecipeMatrix() {
        for (int slot : kRecipe) {
            inventory[slot] = null;
        }
    }

    @Override
    public void closeChest() {
    }

    private boolean valueIn(int value, int[] arr) {
        if (arr == null) {
            return false;
        }
        for (int v : arr) {
            if (value == v) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true iff the given inventory slot is a "ghost" slot used
     * to show the output of the configured recipe. Ghost output items don't
     * really exist and should never be dumped or extracted, except when the
     * recipe is crafted.
     *
     * @param slotId Slot Id to check.
     * @return true iff the slot is a "ghost" slot for the recipe output.
     */
    public boolean isGhostOutputSlot(int slotId) {
        return valueIn(slotId, kOutput);
    }

    /**
     * Returns true iff the given inventory slot is a "ghost" slot used to show
     * the inputs of a crafting recipe. Items in these slots don't really exist
     * and should never be dumped or extracted.
     *
     * @param slotId Slot Id to check.
     * @return true iff the slot is a "ghost" slot for the recipe.
     */
    public boolean isGhostCraftingRecipeSlot(int slotId) {
        return valueIn(slotId, kRecipe);
    }

    /**
     * Returns true iff the given inventory slot holds a "ghost" item
     * that doesn't really exist.
     *
     * @param slotId Slot Id to check.
     * @return true iff the slot holds a "ghost" item.
     */
    public boolean isGhostSlot(int slotId) {
        return isGhostOutputSlot(slotId) || isGhostCraftingRecipeSlot(slotId);
    }

    /**
     * Returns true iff the given inventory slot can hold a real (non-ghost)
     * item, i.e., one that is really stored in the inventory.
     *
     * @param slotId Slot Id to check.
     * @return true iff the slot can hold a real item.
     */
    public boolean isRealItemSlot(int slotId) {
        return !isGhostSlot(slotId);
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        if (slot == kJournal[0])
            clearRecipeMatrix();
        if (this.inventory[slot] != null) {
            ItemStack itemstack;
            if (slot == kOutput[0]) {
                int toRemove = amount;
                while (toRemove > 0) {
                    if (takeInputStacks()) {
                        takeEnergy(currentRecipe);
                        toRemove--;
                    } else {
                        if (amount == toRemove) {
                            return null;
                        } else {
                            ItemStack result = getStackInSlot(slot).copy();
                            result.stackSize = (amount - toRemove);
                            return result;
                        }
                    }
                }
            }
            if (this.inventory[slot].stackSize <= amount) {
                itemstack = this.inventory[slot];
                this.inventory[slot] = null;
                return itemstack;
            } else {
                itemstack = this.inventory[slot].splitStack(amount);
                if (this.inventory[slot].stackSize == 0)
                    this.inventory[slot] = null;
                return itemstack;
            }
        } else {
            return null;
        }
    }


    @Override
    public ItemStack[] extractItem(boolean doRemove, ForgeDirection direction, int maxItemCount) {
        switch (direction) {
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
            case UNKNOWN:
                return extractOutput(doRemove, maxItemCount);
            case UP:
            case DOWN:
                return extractTestTubes(doRemove, maxItemCount);

        }
        return new ItemStack[0];
    }

    public ItemStack[] extractOutput(boolean doRemove, int maxItemCount) {
        if (currentRecipe == null || !takeStacksFromStorage(false) || !canAffordRecipe(currentRecipe))
            return null;
        ItemStack outputStack = currentRecipe.getOutput().copy();
        ItemStack[] output = new ItemStack[]{outputStack};
        if (doRemove) {
            takeEnergy(currentRecipe);
            takeStacksFromStorage(true);
        }
        return output;
    }

    public ItemStack[] extractTestTubes(boolean doRemove, int maxItemCount) {
        return testTubeTransactor.remove(maxItemCount, doRemove);
    }

    public SynthesisRecipe getCurrentRecipe() {
        return currentRecipe;
    }

    public int getFacing() {
        return worldObj.getBlockMetadata(xCoord, yCoord, zCoord);
    }

    @Override
    public String getInvName() {
        return "container.synthesis";
    }

    @Override
    public LinkedList<ITrigger> getNeighborTriggers(Block block, TileEntity tile) {
        if (tile instanceof TileEntitySynthesis) {
            LinkedList<ITrigger> triggers = new LinkedList<ITrigger>();
            triggers.add(MinechemTriggers.fullEnergy);
            triggers.add(MinechemTriggers.noTestTubes);
            triggers.add(MinechemTriggers.outputJammed);
            return triggers;
        }
        return null;
    }

    @Override
    public LinkedList<ITrigger> getPipeTriggers(IPipe pipe) {
        return null;
    }

    public ItemStack[] getRecipeMatrixItems() {
        return recipeMatrix.copyInventoryToArray();
    }

    @Override
    public int getSizeInventory() {
        return 24;
    }

    public boolean hasEnoughPowerForCurrentRecipe() {
        return currentRecipe != null && this.getEnergyStored() >= currentRecipe.energyCost();
    }

    @Override
    public boolean hasFullEnergy() {
        return hasFullEnergy;
    }

    @Override
    public boolean hasNoTestTubes() {
        boolean hasNoTestTubes = true;
        for (int slot : kBottles) {
            if (this.inventory[slot] != null) {
                hasNoTestTubes = false;
                break;
            }
        }
        return hasNoTestTubes;
    }

    @Override
    public boolean isJammed() {
        int count = 0;
        for (int slot : kBottles) {
            if (inventory[slot] != null) {
                count += inventory[slot].stackSize;
            }
        }
        return count >= (64 * 4);
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer entityPlayer) {
        double dist = entityPlayer.getDistanceSq((double) xCoord + 0.5D, (double) yCoord + 0.5D, (double) zCoord + 0.5D);
        return worldObj.getBlockTileEntity(xCoord, yCoord, zCoord) != this ? false : dist <= 64.0D;
    }

    @Override
    public void onInventoryChanged() {
        super.onInventoryChanged();
        getRecipeResult();
    }

    public void onOuputPickupFromSlot() {
        if (takeInputStacks())
            takeEnergy(currentRecipe);
    }

    @Override
    public void readFromNBT(NBTTagCompound nbtTagCompound) {
        super.readFromNBT(nbtTagCompound);
        NBTTagList inventoryTagList = nbtTagCompound.getTagList("inventory");
        inventory = MinechemHelper.readTagListToItemStackArray(inventoryTagList, new ItemStack[getSizeInventory()]);

    }

    public void sendUpdatePacket() {
        PacketSynthesisUpdate packet = new PacketSynthesisUpdate(this);
        int dimensionID = worldObj.provider.dimensionId;
        PacketHandler.getInstance().synthesisUpdateHandler.sendToAllPlayersInDimension(packet, dimensionID);
    }


    @Override
    public void setInventorySlotContents(int slot, ItemStack itemstack) {

        if (slot == kOutput[0] && getStackInSlot(slot) != null) {
            if (itemstack == null) {
                this.decrStackSize(slot, getStackInSlot(slot).stackSize);
                return;
            }
            if (getStackInSlot(slot).getItem() == itemstack.getItem()) {
                if (getStackInSlot(slot).stackSize > itemstack.stackSize) {
                    this.decrStackSize(slot, getStackInSlot(slot).stackSize - itemstack.stackSize);
                    return;
                }
            }
        }

        super.setInventorySlotContents(slot, itemstack);
        if (slot == kJournal[0] && itemstack != null)
            onPutJournal(itemstack);
    }

    public boolean takeStacksFromStorage(boolean doTake) {
        if (this.currentRecipe == null) {
            return false;
        }
        List<ItemStack> ingredients = MinechemHelper.convertChemicalsIntoItemStacks(currentRecipe.getShapelessRecipe());
        ItemStack[] storage = storageInventory.copyInventoryToArray();
        for (ItemStack ingredient : ingredients) {
            if (!takeStackFromStorage(ingredient, storage))
                return false;
        }
        if (doTake) {
            addEmptyBottles(currentRecipe.getIngredientCount());
            storageInventory.setInventoryStacks(storage);
        }
        return true;
    }

    @Override
    public void updateEntity() {
        super.updateEntity();
        if (!worldObj.isRemote && (this.didEnergyStoredChange() || this.didEnergyUsageChange()))
            sendUpdatePacket();

        float energyStored = this.getEnergyStored();
        if (energyStored >= this.getMaxEnergyStored(ForgeDirection.UP))
            hasFullEnergy = true;
        if (hasFullEnergy && energyStored < this.getMaxEnergyStored(ForgeDirection.UP) / 2)
            hasFullEnergy = false;

        if (this.getEnergyStored() >= this.getMaxEnergyStored(ForgeDirection.UP))
            hasFullEnergy = true;
        if (hasFullEnergy && this.getEnergyStored() < this.getMaxEnergyStored(ForgeDirection.UP) / 2)
            hasFullEnergy = false;

        if (currentRecipe != null && inventory[kOutput[0]] == null) {
            inventory[kOutput[0]] = currentRecipe.getOutput().copy();
        }
        if (worldObj.getTotalWorldTime() - this.lastRecipeTick > 5) {
            this.lastEnergyUsed = 0;
        }
        for (Object obj : this.partialFluids.keySet().toArray()) {
            if (obj instanceof IMinechemFluid) {
                IMinechemFluid fluid = (IMinechemFluid) obj;
                int currentFluid = this.partialFluids.get(fluid);
                while (currentFluid >= FluidHelper.FLUID_CONSTANT) {

                    ItemStack output = fluid.getOutputStack();
                    this.addStackToInventory(output);
                    currentFluid -= FluidHelper.FLUID_CONSTANT;
                }
                partialFluids.put(((Fluid) obj), currentFluid);
            }
        }
    }

    @Override
    public void validate() {
        super.validate();
        getRecipeResult();
    }

    @Override
    public void writeToNBT(NBTTagCompound nbtTagCompound) {
        super.writeToNBT(nbtTagCompound);
        NBTTagList inventoryTagList = MinechemHelper.writeItemStackArrayToTagList(inventory);
        nbtTagCompound.setTag("inventory", inventoryTagList);
    }

    private void addEmptyBottles(int amount) {
        for (int slot : kBottles) {
            if (inventory[slot] == null) {
                int stackSize = Math.min(amount, getInventoryStackLimit());
                setInventorySlotContents(slot, new ItemStack(MinechemItems.testTube, stackSize));
                amount -= stackSize;
            } else if (inventory[slot].itemID == MinechemItems.testTube.itemID) {
                int stackAddition = getInventoryStackLimit() - inventory[slot].stackSize;
                stackAddition = Math.min(amount, stackAddition);
                inventory[slot].stackSize += stackAddition;
                amount -= stackAddition;
            }
            if (amount <= 0)
                break;
        }
        // Drop the test tubes.
        if (amount > 0) {
            ItemStack tubes = new ItemStack(MinechemItems.testTube, amount);
            MinechemHelper.ejectItemStackIntoWorld(tubes, worldObj, xCoord, yCoord, zCoord);
        }
    }

    private boolean canAffordRecipe(SynthesisRecipe recipe) {
        int energyCost = recipe.energyCost();
        return this.getEnergyStored() >= energyCost;
    }

    private boolean getRecipeResult() {
        ItemStack[] recipeMatrixItems = getRecipeMatrixItems();
        SynthesisRecipe recipe = SynthesisRecipeHandler.instance.getRecipeFromInput(recipeMatrixItems);
        if (recipe != null) {
            inventory[kOutput[0]] = recipe.getOutput().copy();
            currentRecipe = recipe;
            return true;
        } else {
            inventory[kOutput[0]] = null;
            currentRecipe = null;
            return false;
        }
    }

    private void onPutJournal(ItemStack itemstack) {
        ItemStack activeItem = MinechemItems.journal.getActiveStack(itemstack);
        if (activeItem != null) {
            SynthesisRecipe recipe = SynthesisRecipeHandler.instance.getRecipeFromOutput(activeItem);
            setRecipe(recipe);
        }
    }

    public long lastRecipeTick = 0;

    private void takeEnergy(SynthesisRecipe recipe) {
        int energyCost = recipe.energyCost();
        this.lastEnergyUsed = energyCost;
        this.lastRecipeTick = worldObj.getTotalWorldTime();
        this.energyStored -= energyCost;
    }

    private boolean takeStackFromStorage(ItemStack ingredient, ItemStack[] storage) {
        int ingredientAmountLeft = ingredient.stackSize;
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack storageItem = storage[slot];
            if (storageItem != null && Util.stacksAreSameKind(storageItem, ingredient)) {
                int amountToTake = Math.min(storageItem.stackSize, ingredientAmountLeft);
                ingredientAmountLeft -= amountToTake;
                storageItem.stackSize -= amountToTake;
                if (storageItem.stackSize <= 0)
                    storage[slot] = null;
                if (ingredientAmountLeft <= 0)
                    break;
            }
        }
        return ingredientAmountLeft == 0;
    }

    private boolean takeInputStacks() {
        if (takeStacksFromStorage(false)) {
            takeStacksFromStorage(true);
            return true;
        }
        return false;
    }

    public List<ItemStack> getMaximumOutput() {
        return getOutput(0, true);
    }

    public ItemStack getOutputTemplate() {
        ItemStack template = null;
        ItemStack outputStack = inventory[kOutput[0]];
        if (outputStack != null) {
            template = outputStack.copy();
            if (template.stackSize == 0)
                template.stackSize = 1;
        }
        return template;
    }

    public List<ItemStack> getOutput(int amount, boolean all) {
        if (currentRecipe == null)
            return null;
        ItemStack template = getOutputTemplate();
        List<ItemStack> outputs = new ArrayList<ItemStack>();
        ItemStack initialStack = template.copy();
        initialStack.stackSize = 0;
        outputs.add(initialStack);
        while (canTakeOutputStack() && (amount > 0 || all) && takeInputStacks()) {
            takeEnergy(currentRecipe);
            ItemStack output = outputs.get(outputs.size() - 1);
            if (output.stackSize + template.stackSize > output.getMaxStackSize()) {
                int leftOverStackSize = template.stackSize - (output.getMaxStackSize() - output.stackSize);
                output.stackSize = output.getMaxStackSize();
                if (leftOverStackSize > 0) {
                    ItemStack newOutput = template.copy();
                    newOutput.stackSize = leftOverStackSize;
                    outputs.add(newOutput);
                }
            } else {
                output.stackSize += template.stackSize;
            }
            onInventoryChanged();
            amount--;
        }
        return outputs;
    }

    public void setRecipe(SynthesisRecipe recipe) {
        clearRecipeMatrix();
        if (recipe != null) {
            ItemStack[] ingredients = MinechemHelper.convertChemicalArrayIntoItemStackArray(recipe.getShapedRecipe());
            for (int i = 0; i < Math.min(kRecipe.length, ingredients.length); i++) {
                inventory[kRecipe[i]] = ingredients[i];
            }
            onInventoryChanged();
        }
    }

    @Override
    public ItemStack takeEmptyTestTube() {
        return testTubeTransactor.removeItem(true);
    }

    @Override
    public int putEmptyTestTube(ItemStack testTube) {
        return testTubeTransactor.add(testTube, true);
    }

    @Override
    public ItemStack takeOutput() {
        return outputTransactor.removeItem(true);
    }

    @Override
    public int putOutput(ItemStack output) {
        return outputTransactor.add(output, true);
    }

    @Override
    public ItemStack takeInput() {
        return storageTransactor.removeItem(true);
    }

    @Override
    public int putInput(ItemStack input) {
        return storageTransactor.add(input, true);
    }

    @Override
    public ItemStack takeFusionStar() {
        return null;
    }

    @Override
    public int putFusionStar(ItemStack fusionStar) {
        return 0;
    }

    @Override
    public ItemStack takeJournal() {
        return journalTransactor.removeItem(true);
    }

    @Override
    public int putJournal(ItemStack journal) {
        return journalTransactor.add(journal, true);
    }

    @Override
    public String getMachineState() {
        if (currentRecipe == null) {
            return "norecipe";
        } else {
            int energyCost = currentRecipe.energyCost();
            if (this.getEnergyStored() >= energyCost)
                return "powered";
            else
                return "unpowered";
        }
    }

    @Override
    public boolean isInvNameLocalized() {
        return false;
    }


    public int[] getSizeInventorySide(int side) {
        switch (side) {
            case 0:
            case 1:
                return kBottles;
            case 4:
            case 5:
                return kStorage;
            default:
                return kOutput;
        }
    }


    @Override
    public boolean isItemValidForSlot(int i, ItemStack itemstack) {
        // TODO Auto-generated method stub
        return true;
    }


    @Override
    public int[] getAccessibleSlotsFromSide(int var1) {
        //This is so hacky
        //I'm honestly ashamed
        if (var1 == 1) {
            return this.kStorage;
        }
        if (var1 == 0 && takeStacksFromStorage(false)) {
            return this.kOutput;
        }
        return this.kBottles;

    }


    @Override
    public boolean canInsertItem(int i, ItemStack itemstack, int j) {
        // TODO Auto-generated method stub
        return true;
    }

    @Override
    public boolean canExtractItem(int i, ItemStack itemstack, int j) {
        if (i == kOutput[0]) {
            if (takeStacksFromStorage(false)) {
                return true;
            }
            return false;
        }
        return true;
    }


    public HashMap<Fluid, Integer> partialFluids = new HashMap();

    @Override
    public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
        Fluid fluid = FluidRegistry.getFluid(resource.fluidID);

        if (fluid instanceof IMinechemFluid) {

            IMinechemFluid minechemFluid = (IMinechemFluid) fluid;
            if (!doFill) {
                return resource.amount;
            }
            int previousFluid = 0;
            int currentFluid = 0;
            if (!partialFluids.containsKey(fluid)) {
                partialFluids.put(fluid, 0);
            }
            previousFluid = partialFluids.get(fluid);
            currentFluid = (2 * resource.amount) + previousFluid;

            if (currentFluid < FluidHelper.FLUID_CONSTANT) {


                partialFluids.put(fluid, currentFluid);
                return resource.amount;
            }


            while (currentFluid >= FluidHelper.FLUID_CONSTANT) {

                ItemStack output = minechemFluid.getOutputStack();
                this.addStackToInventory(output);
                currentFluid -= FluidHelper.FLUID_CONSTANT;
            }
            partialFluids.put(fluid, currentFluid);

            return resource.amount;

        }
        return 0;

    }

    public void addStackToInventory(ItemStack newStack) {
        for (int i = 0; i < this.storageInventory.getSizeInventory(); i++) {
            ItemStack stack = this.storageInventory.getStackInSlot(i);
            if (stack == null) {
                this.storageInventory.setInventorySlotContents(i, newStack);
                return;
            }
            if (stack.stackSize < 64 && stack.getItem() == newStack.getItem() && stack.getItemDamage() == newStack.getItemDamage()) {
                stack.stackSize++;
                return;
            }
        }
        worldObj.spawnEntityInWorld(new EntityItem(worldObj, xCoord, yCoord + 2, zCoord, newStack));
    }

    @Override
    public FluidStack drain(ForgeDirection from, FluidStack resource,
                            boolean doDrain) {
        return this.drain(from, resource.amount, doDrain);
    }

    @Override
    public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
        int toDrain = maxDrain;
        FluidStack toReturn = null;
        for (int i = 0; i < this.outputInventory.getSizeInventory(); i++) {
            ItemStack stack = this.outputInventory.decrStackSize(kStartOutput, 1);
            if (stack == null) {
                continue;
            }
            Item item = stack.getItem();
            if (item instanceof ItemElement) {
                ItemElement itemMolecule = (ItemElement) item;
                Fluid fluid = FluidHelper.elements.get(ItemElement.getElement(stack));
                if (toReturn == null || fluid.getID() == toReturn.fluidID) {
                    if (toReturn == null) {
                        toReturn = new FluidStack(fluid, 0);
                    }
                    toReturn.amount += FluidHelper.FLUID_CONSTANT;
                }
            }
            if (item instanceof ItemMolecule) {
                ItemMolecule itemMolecule = (ItemMolecule) item;
                Fluid fluid = FluidHelper.molecule.get(ItemMolecule.getMolecule(stack));
                if (toReturn == null || fluid.getID() == toReturn.fluidID) {
                    if (toReturn == null) {
                        toReturn = new FluidStack(fluid, 0);
                    }
                    toReturn.amount += FluidHelper.FLUID_CONSTANT;
                }
            }

        }

        return toReturn;
    }

    @Override
    public boolean canFill(ForgeDirection from, Fluid fluid) {
        return fluid instanceof IMinechemFluid;
    }

    @Override
    public boolean canDrain(ForgeDirection from, Fluid fluid) {
        return fluid instanceof IMinechemFluid;
    }

    public FluidTankInfo getTankInfo(int i) {

        ItemStack stack = this.storageInventory.getStackInSlot(i);
        if (stack == null) {
            return null;
        }
        Item item = stack.getItem();
        if (item instanceof ItemElement) {
            ItemElement itemMolecule = (ItemElement) item;
            Fluid fluid = FluidHelper.elements.get(ItemElement.getElement(stack));
            if (fluid == null) {
                System.out.println("Invalid fluid in minechem machine");
                return null;
                //This should never happen.
            }
            return new FluidTankInfo(new FluidStack(fluid, stack.stackSize * FluidHelper.FLUID_CONSTANT), 6400);


        }

        if (item instanceof ItemMolecule) {
            ItemMolecule itemMolecule = (ItemMolecule) item;
            Fluid fluid = FluidHelper.molecule.get(ItemMolecule.getMolecule(stack));
            if (fluid == null) {
                return null;
                //This should never happen.
            }
            return new FluidTankInfo(new FluidStack(fluid, stack.stackSize * FluidHelper.FLUID_CONSTANT), 6400);
        }

        return null;

    }

    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection from) {
        ArrayList fluids = new ArrayList();
        for (int i = 0; i < this.storageInventory.getSizeInventory(); i++) {
            FluidTankInfo newFluid = this.getTankInfo(i);
            if (newFluid != null) {
                fluids.add(newFluid);
            }
        }
        return (FluidTankInfo[]) fluids.toArray();
    }
}
